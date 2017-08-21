package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.Context;
import android.os.PowerManager;

import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.PushReceiver;
import com.fsck.k9.mail.power.TracingPowerManager;
import com.fsck.k9.mail.power.TracingPowerManager.TracingWakeLock;
import timber.log.Timber;

import static com.fsck.k9.mail.Folder.OPEN_MODE_RO;
import static com.fsck.k9.mail.K9MailLib.PUSH_WAKE_LOCK_TIMEOUT;
import static com.fsck.k9.mail.store.imap.ImapResponseParser.equalsIgnoreCase;


class ImapFolderPusher {
    private static final int IDLE_READ_TIMEOUT_INCREMENT = 5 * 60 * 1000;
    private static final int IDLE_FAILURE_COUNT_LIMIT = 10;
    private static final int MAX_DELAY_TIME = 5 * 60 * 1000; // 5 minutes
    private static final int NORMAL_DELAY_TIME = 5000;

    private final ImapFolder folder;
    private final PushReceiver pushReceiver;
    private final Object threadLock = new Object();
    private IdleConnectionManager connectionManager;
    private final TracingWakeLock wakeLock;
    private final List<ImapResponse> storedUntaggedResponses = new ArrayList<>();
    private Thread listeningThread;
    private volatile boolean stop = false;
    private volatile boolean idling = false;

    ImapFolderPusher(ImapStore store, String folderName, PushReceiver pushReceiver) {
        this.pushReceiver = pushReceiver;

        folder = store.getNewFolder(folderName);
        Context context = pushReceiver.getContext();
        TracingPowerManager powerManager = TracingPowerManager.getPowerManager(context);
        String tag = "ImapFolderPusher " + store.getStoreConfig().toString() + ":" + folderName;
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wakeLock.setReferenceCounted(false);
    }

    public void start() {
        synchronized (threadLock) {
            if (listeningThread != null) {
                throw new IllegalStateException("start() called twice");
            }

            listeningThread = new Thread(new PushRunnable());
            listeningThread.start();
        }
    }

    public void refresh() throws IOException, MessagingException {
        if (idling) {
            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);
            connectionManager.stopIdle();
        }
    }

    public void stop() {
        synchronized (threadLock) {
            if (listeningThread == null) {
                throw new IllegalStateException("stop() called twice");
            }

            stop = true;

            listeningThread.interrupt();
            listeningThread = null;
        }

        if (folder != null && folder.isOpen()) {
            if (K9MailLib.isDebug()) {
                Timber.v("Closing folder to stop pushing for %s", getLogId());
            }

            folder.close();
        } else {
            Timber.w("Attempt to interrupt null connection to stop pushing on folderPusher for %s", getLogId());
        }
    }

    private boolean isUntaggedResponseSupported(ImapResponse response) {
        return (equalsIgnoreCase(response.get(1), "EXISTS") || equalsIgnoreCase(response.get(1), "EXPUNGE") ||
                equalsIgnoreCase(response.get(1), "FETCH") || equalsIgnoreCase(response.get(0), "VANISHED"));
    }

    private String getLogId() {
        return folder.getLogId();
    }

    String getName() {
        return folder.getName();
    }

    private class PushRunnable implements Runnable {
        private int delayTime = NORMAL_DELAY_TIME;
        private int idleFailureCount = 0;
        private boolean needsPoll = false;
        private UntaggedHandler idleResponseHandler = new UntaggedHandler() {
            @Override
            public void handleAsyncUntaggedResponse(ImapResponse response) throws IOException, MessagingException {
                if (stop) {
                    if (K9MailLib.isDebug()) {
                        Timber.d("Got async untagged response: %s, but stop is set for %s", response, getLogId());
                    }

                    connectionManager.stopIdle();
                } else if (response.getTag() == null) {
                    if (response.size() > 1 && isUntaggedResponseSupported(response)) {
                        wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);

                        if (K9MailLib.isDebug()) {
                            Timber.d("Got useful async untagged response: %s for %s", response, getLogId());
                        }

                        synchronized (storedUntaggedResponses) {
                            storedUntaggedResponses.add(response);
                        }
                    } else if (response.isContinuationRequested()) {
                        if (K9MailLib.isDebug()) {
                            Timber.d("Idling %s", getLogId());
                        }

                        connectionManager.startAcceptingDoneContinuation();
                        wakeLock.release();
                    }
                }

                synchronized (storedUntaggedResponses) {
                    if (!connectionManager.areMoreResponsesAvailable()) {
                        processStoredUntaggedResponses();
                    }
                }
            }
        };

        @Override
        public void run() {
            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);

            if (K9MailLib.isDebug()) {
                Timber.i("Pusher starting for %s", getLogId());
            }

            long lastUidNext = -1L;
            while (!stop) {
                try {
                    long oldUidNext = getOldUidNext();

                        /*
                         * This makes sure 'oldUidNext' is never smaller than 'UIDNEXT' from
                         * the last loop iteration. This way we avoid looping endlessly causing
                         * the battery to drain.
                         *
                         * See issue 4907
                         */
                    if (oldUidNext < lastUidNext) {
                        oldUidNext = lastUidNext;
                    }

                    boolean openedNewConnection = openConnectionIfNecessary();

                    if (stop) {
                        break;
                    }

                    boolean pushPollOnConnect = folder.getStore().getStoreConfig().isPushPollOnConnect();
                    if (pushPollOnConnect && (openedNewConnection || needsPoll)) {
                        needsPoll = false;
                        pushReceiver.syncFolder(getName());
                    }

                    if (stop) {
                        break;
                    }

                    long newUidNext = getNewUidNext();
                    lastUidNext = newUidNext;
                    long startUid = getStartUid(oldUidNext, newUidNext);

                    if (newUidNext > startUid) {
                        pushReceiver.syncFolder(getName());
                    } else {
                        if (K9MailLib.isDebug()) {
                            Timber.i("About to IDLE for %s", getLogId());
                        }

                        prepareForIdle();

                        setReadTimeoutForIdle();
                        sendIdle();

                        returnFromIdle();
                    }
                } catch (AuthenticationFailedException e) {
                    reacquireWakeLockAndCleanUp();

                    if (K9MailLib.isDebug()) {
                        Timber.e(e, "Authentication failed. Stopping ImapFolderPusher.");
                    }

                    pushReceiver.authenticationFailed();
                    stop = true;
                } catch (Exception e) {
                    reacquireWakeLockAndCleanUp();

                    if (stop) {
                        Timber.i(e, "Got exception while idling, but stop is set for %s", getLogId());
                    } else {
                        pushReceiver.pushError("Push error for " + getName(), e);
                        Timber.e(e, "Got exception while idling for %s", getLogId());

                        pushReceiver.sleep(wakeLock, delayTime);

                        delayTime *= 2;
                        if (delayTime > MAX_DELAY_TIME) {
                            delayTime = MAX_DELAY_TIME;
                        }

                        idleFailureCount++;
                        if (idleFailureCount > IDLE_FAILURE_COUNT_LIMIT) {
                            Timber.e("Disabling pusher for %s after %d consecutive errors", getLogId(), idleFailureCount);
                            pushReceiver.pushError("Push disabled for " + getName() + " after " + idleFailureCount +
                                    " consecutive errors", e);
                            stop = true;
                        }
                    }
                }
            }

            pushReceiver.setPushActive(getName(), false);

            try {
                if (K9MailLib.isDebug()) {
                    Timber.i("Pusher for %s is exiting", getLogId());
                }

                folder.close();
            } catch (Exception me) {
                Timber.e(me, "Got exception while closing for %s", getLogId());
            } finally {
                wakeLock.release();
            }
        }

        private void reacquireWakeLockAndCleanUp() {
            wakeLock.acquire(PUSH_WAKE_LOCK_TIMEOUT);

            clearStoredUntaggedResponses();
            idling = false;
            pushReceiver.setPushActive(getName(), false);

            try {
                folder.close();
            } catch (Exception me) {
                Timber.e(me, "Got exception while closing for exception for %s", getLogId());
            }
        }

        private long getNewUidNext() throws MessagingException {
            long newUidNext = folder.getUidNext();
            if (newUidNext != -1L) {
                return newUidNext;
            }

            if (K9MailLib.isDebug()) {
                Timber.d("uidNext is -1, using search to find highest UID");
            }

            long highestUid = folder.getHighestUid();
            if (highestUid == -1L) {
                return -1L;
            }

            newUidNext = highestUid + 1;

            if (K9MailLib.isDebug()) {
                Timber.d("highest UID = %d, set newUidNext to %d", highestUid, newUidNext);
            }

            return newUidNext;
        }

        private long getStartUid(long oldUidNext, long newUidNext) {
            long startUid = oldUidNext;
            int displayCount = folder.getStore().getStoreConfig().getDisplayCount();

            if (startUid < newUidNext - displayCount) {
                startUid = newUidNext - displayCount;
            }

            if (startUid < 1) {
                startUid = 1;
            }

            return startUid;
        }

        private void prepareForIdle() {
            pushReceiver.setPushActive(getName(), true);
            idling = true;
        }

        private void sendIdle() throws MessagingException, IOException {
            try {
                try {
                    folder.executeSimpleCommand(Commands.IDLE, idleResponseHandler);
                } finally {
                    connectionManager.stopAcceptingDoneContinuation();
                }
            } catch (IOException e) {
                folder.close();
                throw e;
            }
        }

        private void returnFromIdle() {
            idling = false;
            delayTime = NORMAL_DELAY_TIME;
            idleFailureCount = 0;
        }

        private boolean openConnectionIfNecessary() throws IOException, MessagingException {
            boolean openedConnection = !folder.isOpen();
            folder.open(OPEN_MODE_RO);
            connectionManager = folder.createIdleConnectionManager();

            checkConnectionIdleCapable();

            return openedConnection;
        }

        private void checkConnectionIdleCapable() throws IOException, MessagingException {
            if (!connectionManager.hasIdleCapability()) {
                stop = true;

                String message = "IMAP server is not IDLE capable: " + getLogId();
                pushReceiver.pushError(message, null);

                throw new MessagingException(message);
            }
        }

        private void setReadTimeoutForIdle() throws SocketException {
            int idleRefreshTimeout = folder.getStore().getStoreConfig().getIdleRefreshMinutes() * 60 * 1000;
            connectionManager.setReadTimeout(idleRefreshTimeout + IDLE_READ_TIMEOUT_INCREMENT);
        }

        private void clearStoredUntaggedResponses() {
            synchronized (storedUntaggedResponses) {
                storedUntaggedResponses.clear();
            }
        }

        private void processStoredUntaggedResponses() throws IOException, MessagingException {
            List<ImapResponse> untaggedResponses = getAndClearStoredUntaggedResponses();

            if (K9MailLib.isDebug()) {
                Timber.i("Processing %d untagged responses from previous commands for %s",
                        untaggedResponses.size(), getLogId());
            }

            for (ImapResponse response : untaggedResponses) {
                if ((equalsIgnoreCase(response.get(1), "EXPUNGE") && handleExpungeResponse(response)) ||
                        (equalsIgnoreCase(response.get(1), "FETCH") && handleFetchResponse(response)) ||
                        (equalsIgnoreCase(response.get(1), "EXISTS") && handleExistsResponse()) ||
                        (equalsIgnoreCase(response.get(0), "VANISHED") && handleVanishedResponse(response))) {
                    pushReceiver.syncFolder(getName());
                    break;
                }
            }
        }

        private List<ImapResponse> getAndClearStoredUntaggedResponses() {
            synchronized (storedUntaggedResponses) {
                if (storedUntaggedResponses.isEmpty()) {
                    return Collections.emptyList();
                }

                List<ImapResponse> untaggedResponses = new ArrayList<>(storedUntaggedResponses);
                storedUntaggedResponses.clear();

                return untaggedResponses;
            }
        }

        private long getOldUidNext() {
            long oldUidNext = -1L;
            try {
                String serializedPushState = pushReceiver.getPushState(getName());
                ImapPushState pushState = ImapPushState.parse(serializedPushState);
                oldUidNext = pushState.uidNext;

                if (K9MailLib.isDebug()) {
                    Timber.i("Got oldUidNext %d for %s", oldUidNext, getLogId());
                }
            } catch (Exception e) {
                Timber.e(e, "Unable to get oldUidNext for %s", getLogId());
            }

            return oldUidNext;
        }
    }

    private int getSmallestSeqNum() {
        int smallestSeqNum = folder.getMessageCount() - folder.getStore().getStoreConfig().getDisplayCount() + 1;
        return smallestSeqNum > 0 ? smallestSeqNum : 1;
    }

    private boolean handleExpungeResponse(ImapResponse response) {
        int seqNum = response.getNumber(0);
        boolean performSync = seqNum >= getSmallestSeqNum();
        if (K9MailLib.isDebug()) {
            Timber.d("Got untagged EXPUNGE for msgseq %d for %s", seqNum, getLogId());
            if (!performSync) {
                Timber.d("Message with seqnum %d for %s is too old", seqNum, getLogId());
            }
        }
        return performSync;
    }

    private boolean handleFetchResponse(ImapResponse response) throws IOException, MessagingException {
        int seqNum = response.getNumber(0);
        boolean performSync = seqNum >= getSmallestSeqNum();
        if (K9MailLib.isDebug()) {
            Timber.d("Got untagged FETCH for msgseq %d for %s", seqNum, getLogId());
            if (!performSync) {
                Timber.d("Message with seqnum %d for %s is too old", seqNum, getLogId());
            }
        }

        if (!performSync) {
            return false;
        }

        if (folder.doesConnectionSupportQresync()) {
            ImapStore store = folder.getStore();
            ImapList fetchList = (ImapList) response.getKeyedValue("FETCH");

            String uid = fetchList.getKeyedString("UID");
            long modseq = fetchList.getKeyedList("MODSEQ").getNumber(0);

            ImapMessage message = new ImapMessage(uid, folder);
            ImapUtility.setMessageFlags(fetchList, message, store);

            pushReceiver.messageFlagsChanged(getName(), message);
            pushReceiver.highestModSeqChanged(getName(), modseq);

            if (K9MailLib.isDebug()) {
                Timber.d("Updating flags for local message with UID %s for %s", uid, getLogId());
                Timber.d("Updating HIGHESTMODSEQ to %d for %s", modseq, getLogId());
            }

            return false;
        }
        return true;
    }

    private boolean handleExistsResponse() {
        if (K9MailLib.isDebug()) {
            Timber.d("Got untagged EXISTS for %s", getLogId());
        }
        return true;
    }

    private boolean handleVanishedResponse(ImapResponse response) {
        if (K9MailLib.isDebug()) {
            List<String> vanishedUids = ImapUtility.extractVanishedUids(Collections.singletonList(response));
            String vanishedUidsString = ImapUtility.join(",", vanishedUids);
            Timber.d("Got untagged VANISHED for UIDs %s for %s", vanishedUidsString, getLogId());
        }
        return true;
    }
}
