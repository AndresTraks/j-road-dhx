package com.nortal.jroad.client.dhl;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import com.nortal.jroad.client.dhl.types.ee.riik.schemas.deccontainer.vers21.AccessConditionType;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.deccontainer.vers21.DecContainerDocument;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.deccontainer.vers21.OrganisationType;
import com.nortal.jroad.util.AttachmentUtil;
import junit.framework.TestCase;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xmlbeans.impl.util.Base64;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.nortal.jroad.client.dhl.DhlXTeeService.ContentToSend;
import com.nortal.jroad.client.dhl.DhlXTeeService.GetDvkOrganizationsHelper;
import com.nortal.jroad.client.dhl.DhlXTeeService.ReceivedDocumentsWrapper;
import com.nortal.jroad.client.dhl.DhlXTeeService.ReceivedDocumentsWrapper.ReceivedDocument;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.dhl.FaultDocument.Fault;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.dhl.TagasisideType;

/**
 * @author ats.uiboupin
 */
public class DhlXTeeServiceImplTest extends TestCase {

    private static Log log = LogFactory.getLog(DhlXTeeServiceImplTest.class);
    private static DhlXTeeService dhl;

    private static String senderRegNr;
    private static List<String> receivedDocumentIds;
    private static boolean receivedDocumentsFailed;
    private static List<String> receiveFailedDocumentIds;
    private static Set<String> sentDocIds = new HashSet<String>();
    private static Map<String, String> dvkOrgList;

    private static final String RECEIVE_OUTPUT_DIR = System.getProperty("java.io.tmpdir");
    private static final String DVK_ORGANIZATIONS_CACHEFILE = RECEIVE_OUTPUT_DIR + "/dvkOrganizationsCache.ser";
    private static final File TEST_FILES_TO_SEND_FOLDER = new File("src/test/resources/testFilesToSend");

    private static boolean markOnlyTestDocumentsRead = true;

    private static DhlXTeeService.DvkOrganizationsUpdateStrategy cachePeriodUpdateStrategy //
    = new DhlXTeeService.DvkOrganizationsCacheingUpdateStrategy().setMaxUpdateInterval(24).setTimeUnit(Calendar.HOUR);
    private static List<String> recipients;
    private boolean executedGetSendingOptions;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (dhl == null) {
            // final ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("service-impl-test.xml");
            final ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("client-test.xml");
            dhl = (DhlXTeeService) context.getBean("dhlXTeeService");
        }
        senderRegNr="10391131";
        recipients = Arrays.asList(senderRegNr); // send to sender
    }

    public void testWarmUp() {
        log.debug("warmup done to get better time measure for the first test");
    }

    public void testGetSendingOptions() {
        dvkOrgList = dhl.getSendingOptions();
        writeCacheToFile(dvkOrgList, Calendar.getInstance());
        log.debug("got " + dvkOrgList.size() + " organizations:");
        assertTrue(dvkOrgList.size() > 0);
        for (String regNr : dvkOrgList.keySet()) {
            log.debug("\tregNr: " + regNr + "\t- " + dvkOrgList.get(regNr));
        }
        executedGetSendingOptions = true;
    }

    public void testGetDvkOrganizationsHelper() {
        log.debug("testGetDvkOrganizationsHelper dhl=" + dhl);
        GetDvkOrganizationsHelper dvkOrganizationsHelper = dhl.getDvkOrganizationsHelper();
        dvkOrganizationsHelper.setUpdateStrategy(cachePeriodUpdateStrategy);
        final Object[] cacheAndTime = readCacheFromFile();
        if (cacheAndTime != null) {
            @SuppressWarnings("unchecked")
            final Map<String, String> cacheFromFile = (Map<String, String>) cacheAndTime[0];
            dvkOrganizationsHelper.setDvkOrganizationsCache(cacheFromFile);
            cachePeriodUpdateStrategy.setLastUpdated((Calendar) cacheAndTime[1]);
        }
        // dvkOrganizationsHelper.setUpdateStrategy(neverUpdateStrategy);
        Map<String, String> dvkOrganizationsCache = dvkOrganizationsHelper.getDvkOrganizationsCache();
        assertTrue(dvkOrganizationsCache.size() > 0);
        if (executedGetSendingOptions) { // if executed getSendingOptions service call, lets compare results
            assertEquals(dvkOrgList.size(), dvkOrganizationsCache.size());
            for (String regNr : dvkOrgList.keySet()) {
                String orgName = dvkOrgList.get(regNr);
                String cachedOrgName = dvkOrganizationsCache.get(regNr);
                assertNotNull(orgName);
                assertNotNull(cachedOrgName);
                assertTrue(orgName.equalsIgnoreCase(cachedOrgName));
            }
            String testRegNr = dvkOrgList.keySet().iterator().next();
            String testOrgName = dvkOrgList.get(testRegNr);
            String cachedOrgName = dvkOrganizationsHelper.getOrganizationName(testRegNr);
            assertEquals(cachedOrgName, testOrgName);
        }
    }

    public void testSendDocuments() {
        final Set<ContentToSend> contentsToSend = getContentsToSend();
        DecContainerDocument.DecContainer.Transport.DecRecipient[] recipientsArray = new DecContainerDocument.DecContainer.Transport.DecRecipient[recipients.size()];
        assertTrue(recipients.size() > 0);
        for (int i = 0; i < recipientsArray.length; i++) {
            recipientsArray[i] = getDecRecipient(recipients.get(i));
        }
        final DecContainerDocument.DecContainer.Transport.DecSender sender = getDecSender();
        sentDocIds = dhl.sendDocuments(contentsToSend, recipientsArray, sender, new DhlXTeeService.SendDocumentsDecContainerCallback() {
            @Override
            public void doWithDocument(DecContainerDocument decContainerDocument) {
                String senderOrganizationCode = sender.getOrganisationCode();
                String senderName = dhl.getDvkOrganizationsHelper().getOrganizationName(senderOrganizationCode);

                DecContainerDocument.DecContainer decContainer = decContainerDocument.getDecContainer();

                DecContainerDocument.DecContainer.Recipient recipient = decContainer.addNewRecipient();
                OrganisationType recipientOrganization = recipient.addNewOrganisation();
                recipientOrganization.setOrganisationCode(senderOrganizationCode);
                recipientOrganization.setName(senderName);

                DecContainerDocument.DecContainer.RecordMetadata recordMetadata = decContainer.addNewRecordMetadata();
                recordMetadata.setRecordGuid("c9e45f03-7180-405d-9d4b-8b597e548d40");
                recordMetadata.setRecordType("Väljaminev kiri");
                recordMetadata.setRecordOriginalIdentifier("Väljaminev kiri");
                recordMetadata.setRecordDateRegistered(Calendar.getInstance());
                recordMetadata.setRecordTitle("Pealkiri");

                DecContainerDocument.DecContainer.Access access = decContainer.addNewAccess();
                access.setAccessConditionsCode(AccessConditionType.AVALIK);

                DecContainerDocument.DecContainer.RecordCreator recordCreator = decContainer.addNewRecordCreator();
                OrganisationType creatorOrganization = recordCreator.addNewOrganisation();
                creatorOrganization.setOrganisationCode(senderOrganizationCode);
                creatorOrganization.setName(senderName);

                DecContainerDocument.DecContainer.RecordSenderToDec recordSenderToDec = decContainer.addNewRecordSenderToDec();
                OrganisationType senderOrganization = recordSenderToDec.addNewOrganisation();
                senderOrganization.setOrganisationCode(senderOrganizationCode);
                senderOrganization.setName(senderName);

                DecContainerDocument.DecContainer.DecMetadata decMetadata = decContainer.addNewDecMetadata();
                decMetadata.setDecId(BigInteger.valueOf(99999));
                decMetadata.setDecFolder("/");
                decMetadata.setDecReceiptDate(Calendar.getInstance());
            }
        }, null);
        assertTrue("Supprize! sendDocuments indeed can return multiple dhl_id's! sentDocIds=" + sentDocIds, sentDocIds.size() > 0);
        for (String dhlId : sentDocIds) {
            log.info("\tdocument sent to DVK, dhlId=" + dhlId);
            assertTrue(StringUtils.isNotBlank(dhlId));
        }
    }

    /**
     * Test method for {@link DhlXTeeService#receiveDocuments(int)

     */
    public void testReceiveDocuments() {
        receivedDocumentsFailed = true;
        receivedDocumentIds = new ArrayList<String>(); // using static field to be able to use the result in other tests
        receiveFailedDocumentIds = new ArrayList<String>(); // using static field to be able to use the result in other tests
        System.gc();// perform GC to free max memory for receiving documents before opening remote connection
        final ReceivedDocumentsWrapper receiveDocuments = dhl.receiveDocuments(300);
        assertTrue(receiveDocuments.size() > 0 || sentDocIds.size() == 0);
        for (String dhlId : receiveDocuments) {
            final ReceivedDocument receivedDocument = receiveDocuments.get(dhlId);
            final DecContainerDocument.DecContainer dhlDokument = receivedDocument.getDhlDocumentV2();
            log.debug("dokument element=" + dhlDokument + "'");
            assertTrue(StringUtils.isNotBlank(dhlId));
            DecContainerDocument.DecContainer.Transport transport = dhlDokument.getTransport();
            DecContainerDocument.DecContainer.Transport.DecSender saatja = transport.getDecSender();
            assertTrue(StringUtils.isNotBlank(saatja.getOrganisationCode()));
            log.debug("sender: " + saatja.getOrganisationCode() + " : " + saatja.getPersonalIdCode());
            final List<DecContainerDocument.DecContainer.Transport.DecRecipient> recipients = transport.getDecRecipientList();
            log.debug("document was sent to " + recipients.size() + " recipients:");
            assertTrue(recipients.size() > 0);
            for (DecContainerDocument.DecContainer.Transport.DecRecipient recipient : recipients) {
                String regnr = recipient.getOrganisationCode();
                log.debug("\trecipient:" + regnr + ": " + recipient.getPersonalIdCode());
                assertTrue(StringUtils.isNotBlank(regnr));
            }
            try {
                List<DecContainerDocument.DecContainer.File> dataFileList = dhlDokument.getFileList();
                log.debug("document contain " + dataFileList.size() + " datafiles: " + dataFileList);
                for (DecContainerDocument.DecContainer.File dataFile : dataFileList) {
                    try {
                        File outFile = File.createTempFile("DVK_" + dhlId + "_" + dataFile.getFileGuid() + "_", dataFile.getFileName());
                        log.debug("writing file '" + dataFile.getFileName() + "' from dvk document with dhlId '" + dataFile.getFileGuid() + "'  to file '"
                                + outFile.getAbsolutePath() + "'");
                        OutputStream os = new FileOutputStream(outFile);
                        os.write(Base64.decode(dataFile.getZipBase64Content().getBytes()));
                        os.close();
                        if (!outFile.delete()) {
                            log.warn("didn't manage to delete " + outFile.getAbsolutePath() + "");
                        }
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException("", e);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed write output to temporary file ", e);
                    }
                }
            } catch (RuntimeException e) {
                log.error("can't get dataFileList from dhlDokument:\n" + dhlDokument + "\n\n", e);
                receiveFailedDocumentIds.add(dhlId);
                continue;
            }
            receivedDocumentIds.add(dhlId);
        }
        log.debug("received " + receivedDocumentIds.size() + " documents: " + receivedDocumentIds);
        if (receiveFailedDocumentIds.size() > 0) {
            log.debug("FAILED to receive " + receiveFailedDocumentIds.size() + " documents: " + receiveFailedDocumentIds);
        }
        assertTrue(sentDocIds == null || receivedDocumentIds.containsAll(sentDocIds));
        for (String dhlId : receiveFailedDocumentIds) {
            assertFalse(sentDocIds.contains(dhlId));
        }
        receivedDocumentsFailed = false;
    }

    public void testMarkDocumentsReceivedV2() {
        final Collection<String> dhlIdsToMarkRead;
        if (markOnlyTestDocumentsRead) {
            // don't mark those documents read that were not sent during this test - someone might acutaly
            // be waiting for them to arrive
            dhlIdsToMarkRead = sentDocIds;
        } else {
            if (receivedDocumentsFailed) {
                if (receivedDocumentIds == null) {
                    receivedDocumentIds = new ArrayList<String>();
                }
                // if call to receivedDocuments failed, then marking those documents read, that were sent for testing
                receivedDocumentIds.addAll(sentDocIds);
            }
            dhlIdsToMarkRead = receivedDocumentIds;
        }

        // Do not add arbitrary IDs to dhlIdsToMarkRead for testing here,
        // because the DHX adapter server may fail the request completely with an HTTP 500 error.

        log.info("Starting to mark " + (markOnlyTestDocumentsRead ? "only test" : "received") + " document received - receivedDocumentIds=" + dhlIdsToMarkRead);
        List<TagasisideType> docsToMarkReadV2 = new ArrayList<TagasisideType>();
        for (String dhlId : dhlIdsToMarkRead) {
            addFault(docsToMarkReadV2, dhlId, "1", "Client", "This test didn't like the document ;)",
                    "just testing, probably there was nothing wrong with the document");
        }
        dhl.markDocumentsReceivedV2(docsToMarkReadV2);
    }

    private void addFault(List<TagasisideType> docsToMarkReadV2, String dhlId, String faultcode, String faultactor, String faultString, String faultDetail) {
        TagasisideType failedToReceiveInfo = TagasisideType.Factory.newInstance();
        failedToReceiveInfo.setDhlId(BigInteger.valueOf(Long.valueOf(dhlId)));
        Fault fault = failedToReceiveInfo.addNewFault();
        fault.setFaultcode(faultcode);
        fault.setFaultactor(faultactor);
        fault.setFaultstring(faultString);
        fault.setFaultdetail(faultDetail);
        docsToMarkReadV2.add(failedToReceiveInfo);
    }

    private DecContainerDocument.DecContainer.Transport.DecRecipient getDecRecipient(String regNr) {
      DecContainerDocument.DecContainer.Transport.DecRecipient decRecipient = DecContainerDocument.DecContainer.Transport.DecRecipient.Factory.newInstance();
      decRecipient.setOrganisationCode(regNr);
      log.debug("recipient: " + ToStringBuilder.reflectionToString(decRecipient) + "'");
      return decRecipient;
    }

    private DecContainerDocument.DecContainer.Transport.DecSender getDecSender() {
        DecContainerDocument.DecContainer.Transport.DecSender decSender = DecContainerDocument.DecContainer.Transport.DecSender.Factory.newInstance();
        decSender.setOrganisationCode(senderRegNr);
        log.debug("Sender: " + ToStringBuilder.reflectionToString(decSender) + "'");
        return decSender;
    }

    /**
     * @return content to send
     */
    private static Set<ContentToSend> getContentsToSend() {
        final HashSet<ContentToSend> contentsToSend = new HashSet<ContentToSend>();
        try {
            final ContentToSend content1 = new ContentToSend();
            final ContentToSend content2 = new ContentToSend();

            final ByteArrayOutputStream bos1 = new ByteArrayOutputStream();
            final ByteArrayOutputStream bos2 = new ByteArrayOutputStream();

            BufferedWriter out1 = new BufferedWriter(new OutputStreamWriter(bos1));
            BufferedWriter out2 = new BufferedWriter(new OutputStreamWriter(bos2));

            DateFormat df = new SimpleDateFormat("d HH:mm:ss");
            out1.write("testfile1 " + df.format(Calendar.getInstance().getTime()));
            out1.close();
            out2.write("testfile2 žõäöüš");
            out2.close();

            content1.setFileName("test1.txt");
            content1.setInputStream(new ByteArrayInputStream(bos1.toByteArray()));
            String mimeTypeTextPlain = "text/plain";
            content1.setMimeType(mimeTypeTextPlain);

            content2.setFileName("test2.txt");
            content2.setInputStream(new ByteArrayInputStream(bos2.toByteArray()));
            content2.setMimeType(mimeTypeTextPlain);

            contentsToSend.add(content1);
            contentsToSend.add(content2);
            contentsToSend.add(getContentFromTestFile("document1.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            contentsToSend.add(getContentFromTestFile("tekstifail-iso-8859-1.txt", mimeTypeTextPlain));
            contentsToSend.add(getContentFromTestFile("tekstifail-utf-8.txt", mimeTypeTextPlain));
            contentsToSend.add(getContentFromTestFile("digidocSigned.ddoc", "application/digidoc"));
            final File file = new File("/tmp/dvkTest.rar");
            if (file.exists()) {
                contentsToSend.add(getContentFromFile(null, null, file));
            }
            return contentsToSend;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test content to be sent to DVK", e);
        }
    }

    private static ContentToSend getContentFromTestFile(String fileName, String mimeType) throws FileNotFoundException {
        return getContentFromFile(fileName, mimeType, new File(TEST_FILES_TO_SEND_FOLDER, fileName));
    }

    private static ContentToSend getContentFromFile(String fileName, String mimeType, File fileToSend) throws FileNotFoundException {
        final ContentToSend content = new ContentToSend();
        content.setInputStream(new FileInputStream(fileToSend));
        content.setFileName(fileName != null ? fileName : fileToSend.getName());
        content.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
        content.setId(AttachmentUtil.getUniqueCid());
        return content;
    }

    private void writeCacheToFile(Map<String, String> dvkOrganizationsCache, Calendar updateTime) {
        try {
            File serFile = new File(DVK_ORGANIZATIONS_CACHEFILE);
            if (!serFile.exists()) {
                serFile.createNewFile();
            }
            ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(serFile));
            oos.writeObject(updateTime);
            oos.writeObject(dvkOrganizationsCache);
            IOUtils.closeQuietly(oos);
            log.debug("wrote data to " + serFile.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize organizations cache to file", e);
        }
    }

    private Object[] readCacheFromFile() {
        File serFile = new File(DVK_ORGANIZATIONS_CACHEFILE);
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(serFile));
            Calendar cal = (Calendar) ois.readObject();
            @SuppressWarnings("unchecked")
            Map<String, String> dvkOrganizationsCache = (Map<String, String>) ois.readObject();
            return new Object[] { dvkOrganizationsCache, cal };
        } catch (FileNotFoundException e) {
            log.debug("Didn't find serialized cache file");
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Error reading in cache file", e);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

}
