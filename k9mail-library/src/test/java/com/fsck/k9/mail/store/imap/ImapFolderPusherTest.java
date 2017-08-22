package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import android.content.Context;

import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.K9LibRobolectricTestRunner;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.PushReceiver;
import com.fsck.k9.mail.power.TracingPowerManager.TracingWakeLock;
import com.fsck.k9.mail.store.StoreConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.shadows.ShadowApplication;

import static com.fsck.k9.mail.store.imap.ImapResponseHelper.createImapResponse;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(K9LibRobolectricTestRunner.class)
@SuppressWarnings("unchecked")
public class ImapFolderPusherTest {

    private static final String FOLDER_NAME = "Folder";
    private static final long UID_NEXT = 123L;
    private static final int SMALLEST_SEQ_NUM = 26;
    private static final int DISPLAY_COUNT = 75;

    private ImapFolderPusher folderPusher;

    @Mock
    private ImapStore store;
    @Mock
    private StoreConfig storeConfig;
    @Mock
    private ImapFolder folder;
    @Mock
    private IdleConnectionManager connectionManager;
    @Mock
    private PushReceiver pushReceiver;
    @Captor
    private ArgumentCaptor<Message> messageCaptor;
    private CountDownLatch latch1;
    private CountDownLatch latch2;
    private CountDownLatch latch3;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        configurePushReceiver();
        configureStore();
        configureFolder();

        folderPusher = new ImapFolderPusher(store, FOLDER_NAME, pushReceiver);
        latch1 = new CountDownLatch(1);
        latch2 = new CountDownLatch(1);
        latch3 = new CountDownLatch(1);
    }

    @Test
    public void refresh_whileIdling_shouldSendDoneFollowedByIdle() throws Exception {
        when(folder.executeSimpleCommand(eq(Commands.IDLE), any(UntaggedHandler.class))).thenAnswer(
                new Answer<Object>() {
                    @Override
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        UntaggedHandler untaggedHandler = invocation.getArgumentAt(1, UntaggedHandler.class);
                        untaggedHandler.handleAsyncUntaggedResponse(createImapResponse("+ idling"));
                        latch1.countDown();
                        latch2.await();
                        return null;
                    }
                }).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                latch3.countDown();
                return null;
            }
        });
        folderPusher.start();
        latch1.await();
        folderPusher.refresh();
        latch2.countDown();
        latch3.await();

        InOrder inOrder = inOrder(connectionManager, folder);
        inOrder.verify(connectionManager).startAcceptingDoneContinuation();
        inOrder.verify(connectionManager).stopIdle();
        inOrder.verify(folder).executeSimpleCommand(eq(Commands.IDLE), any(UntaggedHandler.class));

        folderPusher.stop();
    }

    @Test
    public void pushRunnable_withAccountSetToPushPollOnConnect_shouldSyncFolderOnConnect() throws Exception {
        when(storeConfig.isPushPollOnConnect()).thenReturn(true);
        setupAndRunFolderPusherWithSingleResponse(null);

        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withAccountNotSetToPushPollOnConnect_shouldNotSyncFolderOnConnect() throws Exception {
        when(storeConfig.isPushPollOnConnect()).thenReturn(false);
        setupAndRunFolderPusherWithSingleResponse(null);

        verify(pushReceiver, never()).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withUidNextChanged_shouldSyncFolderBeforeIdling() throws Exception {
        when(pushReceiver.getPushState(FOLDER_NAME)).thenReturn("uidNext=" + UID_NEXT);
        when(folder.getUidNext()).thenReturn(UID_NEXT + 1);

        setupAndRunFolderPusherWithSingleResponse(null);

        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withUidNextNotChanged_shouldNotSyncFolderBeforeIdling() throws Exception {
        when(pushReceiver.getPushState(FOLDER_NAME)).thenReturn("uidNext=" + UID_NEXT);
        when(folder.getUidNext()).thenReturn(UID_NEXT);

        setupAndRunFolderPusherWithSingleResponse(null);

        verify(pushReceiver, never()).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withExpungeResponseForLocallyAvailableMessage_shouldTriggerFolderSync()
            throws Exception {
        String response = String.format(Locale.US, "* %d EXPUNGE", SMALLEST_SEQ_NUM + 1);
        setupAndRunFolderPusherWithSingleResponse(response);

        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withExpungeResponseForLocallyUnavailableMessage_shouldNotTriggerFolderSync()
            throws Exception {
        String response = String.format(Locale.US, "* %d EXPUNGE", SMALLEST_SEQ_NUM - 1);
        setupAndRunFolderPusherWithSingleResponse(response);

        verify(pushReceiver, never()).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withFetchResponseForLocallyAvailableMessage_shouldTriggerFolderSync()
            throws Exception {
        String response = String.format(Locale.US, "* %d FETCH (FLAGS (\\Seen))", SMALLEST_SEQ_NUM + 1);
        setupAndRunFolderPusherWithSingleResponse(response);

        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withFetchResponseForLocallyUnavailableMessage_shouldNotTriggerFolderSync()
            throws Exception {
        String response = String.format(Locale.US, "* %d FETCH (FLAGS (\\Seen))", SMALLEST_SEQ_NUM - 1);
        setupAndRunFolderPusherWithSingleResponse(response);

        verify(pushReceiver, never()).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withFetchResponseForLocallyAvailableMessageAndQresyncEnabled_shouldUpdateLocalMessage()
            throws Exception {
        when(folder.doesConnectionSupportQresync()).thenReturn(true);
        String response = String.format(Locale.US, "* %d FETCH (UID 99 FLAGS (\\Seen) MODSEQ (190))",
                SMALLEST_SEQ_NUM + 1);
        setupAndRunFolderPusherWithSingleResponse(response);

        verify(pushReceiver).messageFlagsChanged(eq(FOLDER_NAME), messageCaptor.capture());
        Message changedMessage = messageCaptor.getValue();
        assertEquals(changedMessage.getUid(), "99");
        assertEquals(changedMessage.getFlags(), singleton(Flag.SEEN));
        verify(pushReceiver).highestModSeqChanged(FOLDER_NAME, 190);
        verify(pushReceiver, never()).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withFetchResponseForLocallyUnavailableMessageAndQresyncEnabled_shouldNotDoAnything()
            throws Exception {
        when(folder.doesConnectionSupportQresync()).thenReturn(true);
        String response = String.format(Locale.US, "* %d FETCH (UID 99 FLAGS (\\Seen) MODSEQ (190))",
                SMALLEST_SEQ_NUM - 1);
        setupAndRunFolderPusherWithSingleResponse(response);

        verify(pushReceiver, never()).messageFlagsChanged(eq(FOLDER_NAME), any(Message.class));
        verify(pushReceiver, never()).highestModSeqChanged(eq(FOLDER_NAME), anyLong());
        verify(pushReceiver, never()).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withExistsResponse_shouldTriggerFolderSync() throws Exception {
        setupAndRunFolderPusherWithSingleResponse("* 250 EXISTS");

        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withVanishedResponse_shouldTriggerFolderSync() throws Exception {
        setupAndRunFolderPusherWithSingleResponse("* VANISHED 170");

        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    @Test
    public void pushRunnable_withAuthenticationFailed_shouldNotifyReceiver() throws Exception {
        latch1 = new CountDownLatch(2);
        doThrow(AuthenticationFailedException.class).when(folder).open(Folder.OPEN_MODE_RO);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                latch1.countDown();
                return null;
            }
        }).when(folder).close();
        folderPusher.start();
        latch1.await();

        verify(pushReceiver).authenticationFailed();
    }

    @Test
    public void pushRunnable_withExceptionThrown_shouldNotifyReceiverAndSleep() throws Exception {
        latch1 = new CountDownLatch(2);
        when(folder.executeSimpleCommand(eq(Commands.IDLE), any(UntaggedHandler.class)))
                .thenThrow(MessagingException.class);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                latch1.countDown();
                return null;
            }
        }).when(folder).open(Folder.OPEN_MODE_RO);
        folderPusher.start();
        latch1.await();

        InOrder inOrder = inOrder(pushReceiver);
        inOrder.verify(pushReceiver).setPushActive(FOLDER_NAME, true);
        inOrder.verify(pushReceiver).setPushActive(FOLDER_NAME, false);
        inOrder.verify(pushReceiver).pushError(anyString(), any(MessagingException.class));
        inOrder.verify(pushReceiver).sleep(any(TracingWakeLock.class), anyLong());
        folderPusher.stop();
    }

    private void configurePushReceiver() {
        Context context = ShadowApplication.getInstance().getApplicationContext();
        when(pushReceiver.getContext()).thenReturn(context);
        when(pushReceiver.getPushState(FOLDER_NAME)).thenReturn("uidNext=" + UID_NEXT);
    }

    private void configureStore() {
        when(store.getNewFolder(FOLDER_NAME)).thenReturn(folder);
        when(store.getStoreConfig()).thenReturn(storeConfig);
        when(storeConfig.getDisplayCount()).thenReturn(DISPLAY_COUNT);
        when(storeConfig.isPushPollOnConnect()).thenReturn(false);
    }

    private void configureFolder() throws IOException, MessagingException {
        when(folder.getName()).thenReturn(FOLDER_NAME);
        when(folder.getStore()).thenReturn(store);
        when(folder.createIdleConnectionManager()).thenReturn(connectionManager);
        when(folder.getUidNext()).thenReturn(UID_NEXT);
        when(folder.getMessageCount()).thenReturn(SMALLEST_SEQ_NUM + DISPLAY_COUNT - 1);
        when(connectionManager.hasIdleCapability()).thenReturn(true);
        when(connectionManager.areMoreResponsesAvailable()).thenReturn(false);
        when(folder.doesConnectionSupportQresync()).thenReturn(false);
    }

    private void setupAndRunFolderPusherWithSingleResponse(final String response) throws Exception {
        when(folder.executeSimpleCommand(eq(Commands.IDLE), any(UntaggedHandler.class))).thenAnswer(
                new Answer<Object>() {
                    @Override
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        UntaggedHandler untaggedHandler = invocation.getArgumentAt(1, UntaggedHandler.class);
                        untaggedHandler.handleAsyncUntaggedResponse(createImapResponse("+ idling"));
                        if (response != null) {
                            untaggedHandler.handleAsyncUntaggedResponse(createImapResponse(response));
                        }
                        folderPusher.stop();
                        latch1.countDown();
                        return null;
                    }
                }).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                return null;
            }
        });
        folderPusher.start();
        latch1.await();
    }
}
