package com.fsck.k9.mail.store.imap;


import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import android.content.Context;

import com.fsck.k9.mail.K9LibRobolectricTestRunner;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.PushReceiver;
import com.fsck.k9.mail.store.StoreConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.shadows.ShadowApplication;

import static com.fsck.k9.mail.store.imap.ImapResponseHelper.createImapResponse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(K9LibRobolectricTestRunner.class)
public class ImapFolderPusherTest {

    private static final String FOLDER_NAME = "Folder";

    private ImapFolderPusher folderPusher;

    @Mock
    private ImapStore store;
    @Mock
    private ImapFolder folder;
    @Mock
    private ImapConnection connection;
    @Mock
    private PushReceiver pushReceiver;

    private CountDownLatch latch;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        configurePushReceiver();
        configureStore();
        configureFolder();

        folderPusher = new ImapFolderPusher(store, FOLDER_NAME, pushReceiver);
    }

    @Test
    public void processStoredUntaggedResponses_withExpungeResponse_shouldTriggerFolderSync() throws Exception {
         latch = new CountDownLatch(1);

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                UntaggedHandler untaggedHandler = invocation.getArgumentAt(1, UntaggedHandler.class);
                untaggedHandler.handleAsyncUntaggedResponse(createImapResponse("* 3 EXPUNGE"));
                latch.countDown();
                return null;
            }
        }).when(folder).executeSimpleCommand(eq(Commands.IDLE), any(UntaggedHandler.class));

        folderPusher.start();

        latch.await();
        verify(pushReceiver).syncFolder(FOLDER_NAME);
    }

    private void configurePushReceiver() {
        Context context = ShadowApplication.getInstance().getApplicationContext();
        when(pushReceiver.getContext()).thenReturn(context);
        when(pushReceiver.getPushState(FOLDER_NAME)).thenReturn("uidNext=5");
    }

    private void configureStore() {
        when(store.getNewFolder(FOLDER_NAME)).thenReturn(folder);
        StoreConfig storeConfig = mock(StoreConfig.class);
        when(store.getStoreConfig()).thenReturn(storeConfig);
        when(storeConfig.getDisplayCount()).thenReturn(5);
    }

    private void configureFolder() throws IOException, MessagingException {
        when(folder.getName()).thenReturn(FOLDER_NAME);
        when(folder.getStore()).thenReturn(store);
        when(folder.getConnection()).thenReturn(connection);
        when(folder.getUidNext()).thenReturn(5L);
        when(folder.getMessageCount()).thenReturn(5);
        when(connection.isIdleCapable()).thenReturn(true);
        when(connection.areMoreResponsesAvailable()).thenReturn(false);
    }
}
