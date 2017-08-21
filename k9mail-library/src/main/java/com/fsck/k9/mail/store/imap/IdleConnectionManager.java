package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.net.SocketException;

import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.store.RemoteStore;


class IdleConnectionManager {

    private ImapConnection connection;
    private boolean acceptDoneContinuation = false;

    IdleConnectionManager(ImapConnection connection) {
        this.connection = connection;
    }

    boolean hasIdleCapability() throws IOException, MessagingException {
        return connection.hasCapability(Capabilities.IDLE);
    }

    void setReadTimeout(int readTimeout) throws SocketException {
        connection.setReadTimeout(readTimeout);
    }

    boolean areMoreResponsesAvailable() throws IOException {
        return connection.areMoreResponsesAvailable();
    }

    synchronized void startAcceptingDoneContinuation() {
        if (connection == null) {
            throw new NullPointerException("connection must not be null");
        }

        acceptDoneContinuation = true;
    }

    synchronized void stopAcceptingDoneContinuation() {
        acceptDoneContinuation = false;
        connection = null;
    }

    synchronized void stopIdle() {
        if (acceptDoneContinuation) {
            acceptDoneContinuation = false;
            sendDone();
        }
    }

    private void sendDone() {
        try {
            setReadTimeout(RemoteStore.SOCKET_READ_TIMEOUT);
            connection.sendContinuation("DONE");
        } catch (IOException e) {
            connection.close();
        }
    }
}
