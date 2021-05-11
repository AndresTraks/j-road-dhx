package com.nortal.jroad.client.dhl;

import java.io.*;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.nortal.jroad.client.dhl.types.ee.riik.schemas.deccontainer.vers21.DecContainerDocument;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.dhl.*;
import com.nortal.jroad.client.dhl.types.ee.riik.xrd.dhl.producers.producer.dhl.*;
import com.nortal.jroad.client.service.configuration.provider.XRoadServiceConfigurationProvider;
import com.nortal.jroad.client.service.consumer.XRoadConsumer;
import org.apache.commons.codec.binary.Base64OutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.ProxyInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ws.commons.util.Base64;
import org.apache.xmlbeans.SchemaType;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.mime.Attachment;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.nortal.jroad.client.dhl.types.ee.riik.schemas.dhl.TransportDocument.Transport;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.dhl_meta_automatic.DhlDokIDType;
import com.nortal.jroad.client.dhl.types.ee.sk.digiDoc.v13.DataFileType;
import com.nortal.jroad.client.dhl.types.ee.sk.digiDoc.v13.SignedDocType;
import com.nortal.jroad.client.exception.XRoadServiceConsumptionException;
import com.nortal.jroad.client.service.XRoadDatabaseService;
import com.nortal.jroad.client.service.extractor.CustomExtractor;
import com.nortal.jroad.model.XRoadAttachment;
import com.nortal.jroad.model.XRoadMessage;
import com.nortal.jroad.model.XmlBeansXRoadMessage;
import com.nortal.jroad.util.AttachmentUtil;
import org.xml.sax.SAXException;

/**
 * @author Ats Uiboupin
 */
public class DhlXTeeServiceImpl extends XRoadDatabaseService implements DhlXTeeService {
    private static Log log = LogFactory.getLog(DhlXTeeServiceImpl.class);

    // START: XTEE DVK service names and versions
    private static final String SEND_DOCUMENTS = "sendDocuments";
    private static final String SEND_DOCUMENTS_VERSION = "v4";

    private static final String GET_SENDING_OPTIONS = "getSendingOptions";
    private static final String GET_SENDING_OPTIONS_VERSION = "v2";

    private static final String GET_SEND_STATUS = "getSendStatus";
    private static final String GET_SEND_STATUS_VERSION = "v2";

    private static final String RECEIVE_DOCUMENTS = "receiveDocuments";
    private static final String RECEIVE_DOCUMENTS_VERSION = "v4";// v3 existed, but removed from wsdl, as it didn't work

    private static final String MARK_DOCUMENTS_RECEIVED = "markDocumentsReceived";
    private static final String MARK_DOCUMENTS_RECEIVED_VERSION_2 = "v2";

    // END: XTEE DVK service names and versions

    private static final String DVK_MESSAGE_CHARSET = "UTF-8";

    private GetDvkOrganizationsHelper dvkOrganizationsHelper;

    private final SendDocumentsHelper sendDocumentsHelper;

    private String receivedDocumentsResponseRootElemName = "dokumendid";
    private String receivedDocumentsFolder;
    private String sentDocumentsFolder;

    private static final Map<String, Class<?>> PROP_CLASS_MAPPING = new HashMap<String, Class<?>>();

    static {
        PROP_CLASS_MAPPING.put(GetSendStatusProp.Edastus.STAATUS.getName(), String.class);
        PROP_CLASS_MAPPING.put(GetSendStatusProp.Edastus.LOETUD.getName(), Date.class);
        PROP_CLASS_MAPPING.put(GetSendStatusProp.Edastus.REGNR.getName(), String.class);
        PROP_CLASS_MAPPING.put(GetSendStatusProp.Edastus.ISIKUKOOD.getName(), String.class);
    }

    public DhlXTeeServiceImpl() {
        this(null);
    }

    public DhlXTeeServiceImpl(DvkOrganizationsUpdateStrategy updateStrategy) {
        this.dvkOrganizationsHelper = new GetDvkOrganizationsHelperImpl();
        if (updateStrategy != null) {
            dvkOrganizationsHelper.setUpdateStrategy(updateStrategy);
        }
        this.sendDocumentsHelper = this.new SendDocumentsHelper();
    }

    public void markDocumentsReceivedV2(Collection<TagasisideType> receivedDocsInfos) {
        String queryMethod = getDatabase() + "." + MARK_DOCUMENTS_RECEIVED + "." + MARK_DOCUMENTS_RECEIVED_VERSION_2;
        MarkDocumentsReceivedDocument.MarkDocumentsReceived markDocumentsReceived =
            MarkDocumentsReceivedDocument.MarkDocumentsReceived.Factory.newInstance();
        MarkDocumentsReceivedV2RequestType keha = markDocumentsReceived.addNewKeha();
        byte[] attachmentBody = createMarkDocumentsReceivedV2AttachmentBody(receivedDocsInfos);
        keha.addNewDokumendid();
        final XRoadAttachment attachment = setDokumendidHrefToAttachment(attachmentBody, keha);

        log.debug("executing " + queryMethod);
        try {
            XmlBeansXRoadMessage<MarkDocumentsReceivedDocument.MarkDocumentsReceived> message =
                new XmlBeansXRoadMessage<MarkDocumentsReceivedDocument.MarkDocumentsReceived>(markDocumentsReceived, Collections.singletonList(attachment));
            XRoadMessage<MarkDocumentsReceivedResponseDocument.MarkDocumentsReceivedResponse> response = send(message, MARK_DOCUMENTS_RECEIVED,
                    MARK_DOCUMENTS_RECEIVED_VERSION_2);
            if (!"OK".equalsIgnoreCase(response.getContent().getKeha())) {
                throw new RuntimeException("Service didn't respond with 'OK': " + response.getContent().getKeha());
            }
        } catch (XRoadServiceConsumptionException e) {
            throw new WrappedXRoadServiceConsumptionException(e);
        }
    }

    public ReceivedDocumentsWrapper receiveDocuments(int maxNrOfDocuments) {
        String queryMethod = getDatabase() + "." + RECEIVE_DOCUMENTS + "." + RECEIVE_DOCUMENTS_VERSION;
        ReceiveDocumentsDocument.ReceiveDocuments receiveDocumentsRequest =
            ReceiveDocumentsDocument.ReceiveDocuments.Factory.newInstance();
        ReceiveDocumentsV4RequestType request = receiveDocumentsRequest.addNewKeha();
        request.setArv(BigInteger.valueOf(maxNrOfDocuments));
        log.debug("executing " + queryMethod);
        try {
            XmlBeansXRoadMessage<ReceiveDocumentsDocument.ReceiveDocuments> message =
                new XmlBeansXRoadMessage<ReceiveDocumentsDocument.ReceiveDocuments>(receiveDocumentsRequest);
            XRoadMessage<ReceiveDocumentsV4ResponseTypeUnencoded> response =
                send(message, RECEIVE_DOCUMENTS, RECEIVE_DOCUMENTS_VERSION, null, new ReceiveDocumentExtractor());
            return new ReceivedDocumentsWrapperImpl(response, maxNrOfDocuments);
        } catch (XRoadServiceConsumptionException e) {
            throw new WrappedXRoadServiceConsumptionException(e);
        }
    }

    static class UnzipThreadVO {
        private final File file;
        private OutputStream outputStream;
        private Thread unzipThread;
        private InputStreamWithOutputProxy wrappedInputStream;
        private List<Throwable> ungzipThreadThrowableList = new LinkedList<Throwable>();

        public UnzipThreadVO(File file) {
            this.file = file;
        }

        public File getFile() {
            return file;
        }

        public OutputStream getOutputStream() {
            return outputStream;
        }

        public void setOutputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        public Thread getUnzipThread() {
            return unzipThread;
        }

        public void setUnzipThread(Thread unzipThread) {
            this.unzipThread = unzipThread;
        }

        public List<Throwable> getUngzipThreadThrowableList() {
            return ungzipThreadThrowableList;
        }

        public void setUngzipThreadThrowableList(List<Throwable> ungzipThreadThrowableList) {
            this.ungzipThreadThrowableList = ungzipThreadThrowableList;
        }

        public void setWrappedInputStream(InputStreamWithOutputProxy wrappedInputStream) {
            this.wrappedInputStream = wrappedInputStream;
        }

        public InputStreamWithOutputProxy getWrappedInputStream() {
            return wrappedInputStream;
        }

    }

    private static <T> List<T> getTypeFromDecodedSoapArray(String decodedResponseString, Class<T> responseClass) {
        SchemaType unencodedType = null;
        try {
            unencodedType = (SchemaType) responseClass.getField("type").get(null);
            log.debug("unencodedType=" + unencodedType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get value of '" + responseClass.getCanonicalName() + ".type' to get corresponding SchemaType object: ", e);
        }
        if (StringUtils.isBlank(decodedResponseString)) {
            return Collections.emptyList();
        }
        ArrayList<T> result = null;
        try {
            decodedResponseString = "<root>" + decodedResponseString + "</root>";
            XmlOptions options = new XmlOptions();
            if (log.isTraceEnabled()) {
                log.trace("Starting to parse '" + decodedResponseString + "' to class: " + responseClass.getCanonicalName() + "\n\n");
            }
            // XXX: potential optimization idea that could allow us to receive twice as big files as we can receive now:
            // use following line to create xmlObject,
            // but to avoid failure we must prepend to original inputStream some xml start element and append corresponding end element
            // but since rest of the inputStream is zipped and encoded to base64, then prepended and appended root element must also be zipped and encoded
            // XmlObject xmlObject = XmlObject.Factory.parse(unzip(decodeFromBase64(inputStream)), options);
            XmlObject xmlObject = XmlObject.Factory.parse(decodedResponseString, options);
            XmlCursor cursor = xmlObject.newCursor();
            cursor.toFirstChild();
            cursor.toFirstChild();
            options.setDocumentType(unencodedType);

            result = new ArrayList<T>();
            int i = 0;
            do {
                if (log.isTraceEnabled()) {
                    cursor.getObject();
                    log.trace("Type of token " + (i++) + ": '" + cursor.currentTokenType() + "'");
                }
                @SuppressWarnings("unchecked")
                T resultItem = (T) XmlObject.Factory.parse(cursor.getDomNode(), options);
                result.add(resultItem);
            } while (cursor.toNextSibling());
            cursor.dispose();
        } catch (XmlException e) {
            throw new RuntimeException("Failed to parse '" + decodedResponseString + "' to class: " + responseClass.getCanonicalName(), e);
        }
        return result;
    }

    private static String unzipAndDecodeDvkMsg(InputStream inputStream) {
        String responseString;
        try {
            responseString = new String(unzipAndDecode(inputStream), DVK_MESSAGE_CHARSET);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Failed to encode responseString to " + DVK_MESSAGE_CHARSET, e);
        }
        return responseString;
    }

    public class ReceivedDocumentsWrapperImpl extends AbstractMap<String, ReceivedDocumentsWrapper.ReceivedDocument> implements ReceivedDocumentsWrapper {
        private final List<VersionedDhlDocument> receivedDocuments;
        private final Map<String /* dhlId */, ReceivedDocument> dhlDocumentsMap;
        private UnzipThreadVO unzipThreadVO;
        private File responseXml;
        private final int maxNrOfDocuments;

        public ReceivedDocumentsWrapperImpl(XRoadMessage<ReceiveDocumentsV4ResponseTypeUnencoded> response, int maxNrOfDocuments) {
            List<DhlDokumentType> dokumentDocuments;
            List<DecContainerDocument.DecContainer> decContainerDocuments;
            this.maxNrOfDocuments = maxNrOfDocuments;
            //try {
                //String responseString = unzipAndDecodeDvkMsg(getInputStream(response.getContent().getDokumentList().get(1).getSignedDoc().getAttachments().get(0).getInputStream()));
            decContainerDocuments = (response.getContent() != null)?response.getContent().getDokumendid().getDecContainerList():new ArrayList<DecContainerDocument.DecContainer>();
                //dokumentDocuments = getTypeFromDecodedSoapArray(responseString, DokumentDocument.class);
                //decContainerDocuments = getTypeFromDecodedSoapArray(responseString, DecContainerDocument.class);
            //} catch (IOException e) {
            //    handleResponseFile();
            //    throw new RuntimeException("Failed to get input of attachment ", e);
            //}
            receivedDocuments = new ArrayList<VersionedDhlDocument>();
            dhlDocumentsMap = new HashMap<String, ReceivedDocument>();
            /*
            for (DhlDokumentType dhlDokument : dokumentDocuments) {
                //DhlDokumentType dhlDokument = dokumentDocument.getDokument();
                if (dhlDokument == null) {
                    continue;
                }
                receivedDocuments.add(new VersionedDhlDocument(dhlDokument));
                Metainfo metainfo = dhlDokument.getMetainfo();
                MetainfoHelper metaInfoHelper = new MetainfoHelper(metainfo);
                String dhlId = metaInfoHelper.getDhlId();
                dhlDocumentsMap.put(dhlId, new ReceivedDocumentImpl(dhlDokument, dhlId, metaInfoHelper));
            }
            */

            for (DecContainerDocument.DecContainer decContainer : decContainerDocuments) {
                if (decContainer == null) {
                    continue;
                }
                receivedDocuments.add(new VersionedDhlDocument(decContainer));
                DecContainerDocument.DecContainer.RecordMetadata recordMetadata = decContainer.getRecordMetadata();
                DecContainerDocument.DecContainer.DecMetadata decMetadata = decContainer.getDecMetadata();

                BigInteger dhlId = decMetadata.getDecId();
                dhlDocumentsMap.put(dhlId.toString(), new ReceivedDocumentImpl(decContainer, dhlId.toString()));
            }

            log.debug("received " + receivedDocuments.size() + " documents");
            handleResponseFile();
        }

        /**
         * @param originalInputStream
         * @return returns originalInputStream of the response if <code>receivedDocumentsFolder</code> is not set, <br>
         *         otherwise return wrappedInputStream that will write response into file under <code>receivedDocumentsFolder</code>
         * @throws IOException
         */
        private InputStream getInputStream(InputStream originalInputStream) throws IOException {
            if (StringUtils.isBlank(receivedDocumentsFolder)) {
                return originalInputStream;
            }
            final InputStreamWithOutputProxy wrappedInputStream = new InputStreamWithOutputProxy(originalInputStream);
            log.info("receivedDocumentsFolder=" + receivedDocumentsFolder);
            final File directory = new File(receivedDocumentsFolder);
            if (!directory.exists()) {
                throw new FileNotFoundException("receivedDocumentsFolder '" + receivedDocumentsFolder + "' doesn't exist!");
            }
            unzipThreadVO = new UnzipThreadVO(File.createTempFile("dvk", ".xml", directory));
            prepareFileOutputStream(wrappedInputStream, unzipThreadVO);
            return wrappedInputStream;
        }

        private void prepareFileOutputStream(final InputStreamWithOutputProxy wrappedInputStream, UnzipThreadVO threadVO) throws FileNotFoundException {
            final File outputFile = threadVO.getFile();
            final FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
            if (StringUtils.isNotBlank(receivedDocumentsResponseRootElemName)) {
                try { // add root element - response could contain more than one <dhl:document/>
                    fileOutputStream.write(("<" + receivedDocumentsResponseRootElemName + ">").getBytes());
                } catch (IOException e) {
                    IOUtils.closeQuietly(wrappedInputStream);
                    IOUtils.closeQuietly(fileOutputStream);
                    if (!outputFile.delete()) {
                        log.warn("failed to delete file " + outputFile.getAbsolutePath());
                    }
                    throw new RuntimeException("failed to write start of root xml element to " + outputFile.getAbsolutePath(), e);
                }
            } else if (maxNrOfDocuments > 1) {
                throw new RuntimeException(
                        "Logging response is turned on and response could potentially contain more than 1 document, but receivedDocumentsResponseRootElemName is empty '"
                                + receivedDocumentsResponseRootElemName
                                + "'. To avoid creating invalid xml please set the name for received documents root element (receivedDocumentsResponseRootElemName)!");
            }
            // get's written each time smth is read from wrappedInputStream
            final PipedOutputStream pipedOutputStreamProxy = new PipedOutputStream();
            // kui wrappedInputStream'ist loetakse, siis kogu info edastatakse ka pipedOutputStream'ile
            wrappedInputStream.setOutputStream(pipedOutputStreamProxy);

            final List<Throwable> ungzipThreadThrowableList = threadVO.getUngzipThreadThrowableList();
            final PipedInputStream pipedInputStream;
            try {
                pipedInputStream = new PipedInputStream(pipedOutputStreamProxy);
            } catch (IOException e1) {
                IOUtils.closeQuietly(pipedOutputStreamProxy);
                throw new RuntimeException("failed to unzip input(connecting pipeStream failed)", e1);
            }
            Thread ungzipThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        getDecodeAndUnzipOutputStream(pipedInputStream, fileOutputStream);
                        if (StringUtils.isNotBlank(receivedDocumentsResponseRootElemName)) {
                            try {
                                fileOutputStream.write(("</" + receivedDocumentsResponseRootElemName + ">").getBytes());
                            } catch (IOException e) {
                                throw new RuntimeException("failed to write end of root xml element to " + outputFile.getAbsolutePath(), e);
                            }
                        }
                    } catch (Throwable t) {
                        log.error("error within unzipthread!", t);
                        ungzipThreadThrowableList.add(t);
                    } finally {
                        IOUtils.closeQuietly(pipedInputStream);
                        IOUtils.closeQuietly(fileOutputStream);
                    }
                }
            });
            ungzipThread.start();
            threadVO.setWrappedInputStream(wrappedInputStream);
            threadVO.setUnzipThread(ungzipThread);
        }

        /**
         * If <code>receivedDocumentsFolder</code> was set then renames created file based on dhl'ids that were received, <br>
         * otherwise does nothing
         */
        private void handleResponseFile() {
            if (unzipThreadVO == null) {
                return;
            }
            File xmlFile = unzipThreadVO.getFile();
            syncWithUnzipThread();
            final Set<String> keySet = dhlDocumentsMap.keySet();
            String newFilePrefix = "dvk_" + (new SimpleDateFormat("yyyy.MM.dd-kk.mm.ss,SSS").format(new Date())) + "_";
            if (keySet.size() > 0) {
                final String dvkIds = keySet.toString().replaceAll("\\s", "");
                newFilePrefix = newFilePrefix + dvkIds.substring(1, dvkIds.length() - 1);
                if (dvkIds.length() > 200) {
                    newFilePrefix = newFilePrefix.substring(0, 200) + "..";// limit length just in case receiving a lot of documents
                }
            }
            responseXml = new File(receivedDocumentsFolder, newFilePrefix + ".xml");
            final boolean success = xmlFile.renameTo(responseXml);
            if (!success) {
                log.error("renaming file " + responseXml.getAbsolutePath() + " failed. New name would have been '" + responseXml.getAbsolutePath() + "'");
                responseXml = xmlFile;
            }
        }

        private void syncWithUnzipThread() {
            final Thread unzipThread = unzipThreadVO.getUnzipThread();
            if (unzipThread != null) {
                try {
                    final OutputStream outputStream = unzipThreadVO.getWrappedInputStream().getOutputStream();
                    outputStream.flush();
                    outputStream.close();
                    unzipThread.join();
                } catch (InterruptedException ie) {
                    throw new RuntimeException("unzip thread was interrupted", ie);
                } catch (IOException e) {
                    throw new RuntimeException("flushing proxy output stream failed", e);
                }
                final List<Throwable> ungzipThreadThrowableList = unzipThreadVO.getUngzipThreadThrowableList();
                if (!ungzipThreadThrowableList.isEmpty()) {
                    throw new RuntimeException("ungzip failed", ungzipThreadThrowableList.get(0));
                }
            }
        }

        @Override
        public Iterator<String> iterator() {
            return dhlDocumentsMap.keySet().iterator();
        }

        @Override
        public Set<Entry<String, ReceivedDocument>> entrySet() {
            return dhlDocumentsMap.entrySet();
        }

        // START: getters/setters
        @Override
        public Map<String, ReceivedDocument> getDhlDocumentsMap() {
            return dhlDocumentsMap;
        }

        @Override
        public List<VersionedDhlDocument> getReceivedDocuments() {
            return receivedDocuments;
        }

        @Override
        public File getResponseDocumentsXml() {
            return responseXml;
        }

        // END: getters/setters

        public class ReceivedDocumentImpl implements ReceivedDocument {
            private final String dhlId;
            private final MetainfoHelper metaInfoHelper;
            private final DhlDokumentType dhlDocument;
            private final DecContainerDocument.DecContainer dhlDocumentV2;
            private final DhlDocumentVersion version;

            public ReceivedDocumentImpl(DhlDokumentType dhlDocument, String dhlId, MetainfoHelper metaInfoHelper) {
                version = DhlDocumentVersion.VER_1;
                this.dhlDocument = dhlDocument;
                this.dhlId = dhlId;
                this.metaInfoHelper = metaInfoHelper;
                dhlDocumentV2 = null;
            }

            public ReceivedDocumentImpl(DecContainerDocument.DecContainer dhlDocumentV2, String dhlId) {
                version = DhlDocumentVersion.VER_2;
                this.dhlDocumentV2 = dhlDocumentV2;
                this.dhlId = dhlId;
                metaInfoHelper = null;
                dhlDocument = null;
            }

            private void checkVersion(DhlDocumentVersion versionToCheck) {
                if (!versionToCheck.equals(version)) {
                    throw new RuntimeException("Current version " + version + " does not support this method!");
                }
            }

            @Override
            public DhlDokumentType getDhlDocument() {
                checkVersion(DhlDocumentVersion.VER_1);
                return dhlDocument;
            }

            @Override
            public DecContainerDocument.DecContainer getDhlDocumentV2() {
                checkVersion(DhlDocumentVersion.VER_2);
                return dhlDocumentV2;
            }

            @Override
            public SignedDocType getSignedDoc() {
                checkVersion(DhlDocumentVersion.VER_1);
                return dhlDocument.getSignedDoc();
            }

            public String getDhlId() {
                return dhlId;
            }

            @Override
            public MetainfoHelper getMetaInfoHelper() {
                checkVersion(DhlDocumentVersion.VER_1);
                return metaInfoHelper;
            }

            @Override
            public DhlDocumentVersion getDocumentVersion() {
                return version;
            }

        }

    }

    private Set<String> sendDocuments(XmlObject xmlObject, SendDocumentsRequestCallback requestCallback) {
        byte[] base64Doc = gzipAndEncodeXmlObject(xmlObject);

        SendDocumentsDocument.SendDocuments sendDocuments =
          SendDocumentsDocument.SendDocuments.Factory.newInstance();
        SendDocumentsV4RequestType keha = sendDocuments.addNewKeha();
        Base64BinaryType dokumendid = keha.addNewDokumendid();
        keha.setKaust("/");

        final XRoadAttachment attachment = setDokumendidHrefToAttachment(base64Doc, dokumendid);

        if (requestCallback != null) {
            requestCallback.doWithRequest(keha);
        }

        String queryMethod = getDatabase() + "." + SEND_DOCUMENTS + "." + SEND_DOCUMENTS_VERSION;
        log.debug("executing " + queryMethod);
        try {
            XmlBeansXRoadMessage<SendDocumentsDocument.SendDocuments> message =
                new XmlBeansXRoadMessage<SendDocumentsDocument.SendDocuments>(sendDocuments, Collections.singletonList(attachment));
            XRoadMessage<SendDocumentsV4ResponseTypeUnencoded> response = send(message, SEND_DOCUMENTS, SEND_DOCUMENTS_VERSION,
                null, new SendDocumentsExtractor());

            Set<String> sentDocumentsDhlIds = new HashSet<String>();
            sentDocumentsDhlIds.addAll(response.getContent().getKeha().getDhlIdList());

            return sentDocumentsDhlIds;
        } catch (XRoadServiceConsumptionException e) {
            throw new WrappedXRoadServiceConsumptionException(e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract response " + queryMethod, e);
        }
    }


    public Set<String> sendDocuments(Collection<ContentToSend> contentsToSend, AadressType[] recipients, AadressType sender,
            SendDocumentsDokumentCallback dokumentCallback, SendDocumentsRequestCallback requestCallback) {

        String queryMethod = getDatabase() + "." + SEND_DOCUMENTS + "." + SEND_DOCUMENTS_VERSION;

        DokumentDocument dokumentDocument = sendDocumentsHelper.constructDokumentDocument(contentsToSend, sender, recipients);

        if (dokumentCallback != null) {
            dokumentCallback.doWithDocument(dokumentDocument);
        }

        byte[] base64Doc = gzipAndEncodeXmlObject(dokumentDocument);

        SendDocumentsDocument sd = SendDocumentsDocument.Factory.newInstance();
        SendDocumentsDocument.SendDocuments sendDocumentsRequest = sd.addNewSendDocuments();
        SendDocumentsV4RequestType request = sendDocumentsRequest.addNewKeha();

        request.setDokumendid(null);
        final XRoadAttachment attachment = setDokumendidHrefToAttachment(base64Doc, request);

        if (requestCallback != null) {
            requestCallback.doWithRequest(request);
        }

        log.debug("executing " + queryMethod);
        try {
            XRoadMessage<SendDocumentsResponseDocument> response = send(new XmlBeansXRoadMessage<SendDocumentsDocument>(sd, Collections
                    .singletonList(attachment)), SEND_DOCUMENTS, SEND_DOCUMENTS_VERSION);

            List<DhlDokIDType> dokumentDocuments //
            = getTypeFromGzippedAndEncodedSoapArray(response.getAttachments().get(0).getInputStream(), DhlDokIDType.class);
            Set<String> sentDocumentsDhlIds = new HashSet<String>();
            for (DhlDokIDType dokIDType : dokumentDocuments) {
                sentDocumentsDhlIds.add(dokIDType.getStringValue());
            }
            return sentDocumentsDhlIds;
        } catch (XRoadServiceConsumptionException e) {
            throw new WrappedXRoadServiceConsumptionException(e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract response " + queryMethod, e);
        }
    }

    public Set<String> sendDocuments(Collection<ContentToSend> contentsToSend, DecContainerDocument.DecContainer.Transport.DecRecipient[] recipients, DecContainerDocument.DecContainer.Transport.DecSender sender,
            SendDocumentsDecContainerCallback decContainerCallback, SendDocumentsRequestCallback requestCallback) {

        DecContainerDocument decContainerDocument  = sendDocumentsHelper.constructDecContainerDocument(contentsToSend, sender, recipients);
        if (decContainerCallback != null) {
            decContainerCallback.doWithDocument(decContainerDocument);
        }

        return sendDocuments(decContainerDocument, requestCallback);
    }

    public Map<String/* regNr */, String/* name */> getSendingOptions() {
        String queryMethod = getDatabase() + "." + GET_SENDING_OPTIONS + "." + GET_SENDING_OPTIONS_VERSION;
        log.debug("executing " + queryMethod);
        GetSendingOptionsDocument sd = GetSendingOptionsDocument.Factory.newInstance();
        GetSendingOptionsDocument.GetSendingOptions getSendingOptionsRequest = sd.addNewGetSendingOptions();
        GetSendingOptionsV2RequestType request = getSendingOptionsRequest.addNewKeha();
        try {
            XmlBeansXRoadMessage<GetSendingOptionsDocument> message =
                new XmlBeansXRoadMessage<GetSendingOptionsDocument>(sd);
            final XRoadMessage<GetSendingOptionsV2ResponseType> response = send(message,
                    GET_SENDING_OPTIONS, GET_SENDING_OPTIONS_VERSION);
            if (log.isTraceEnabled()) {
                log.trace("execution result#1 response:\t" + ToStringBuilder.reflectionToString(response) + "'");
            }
            InstitutionArrayType responseContent = response.getContent().getKeha();
            List<InstitutionType> orgList = responseContent.getAsutusList();
            log.debug(orgList.size() + " organisations can use DVK");
            HashMap<String, String> dvkCapableOrganizations = new HashMap<String, String>(orgList.size());
            for (InstitutionType institutionType : orgList) {
                dvkCapableOrganizations.put(institutionType.getRegnr(), institutionType.getNimi());
                log.debug("\t" + institutionType.getRegnr() + ":\t" + institutionType.getNimi());
            }
            return dvkCapableOrganizations;
        } catch (XRoadServiceConsumptionException e) {
            throw new WrappedXRoadServiceConsumptionException(e);
        }
    }

    public Map<String, List<Map<String, Serializable>>> getSendStatuses(Set<String> ids) {
        if (ids == null || ids.size() == 0) {
            log.warn("No documents sent! sentDocIds=" + ids);
            return Collections.emptyMap();
        }
        String queryMethod = getDatabase() + "." + GET_SEND_STATUS + "." + GET_SEND_STATUS_VERSION;
        log.debug("getSendStatused(): query method: " + queryMethod);
        try {
            GetSendStatusV2RequestTypeUnencoded getSendStatusV2RequestTypeUnencoded = GetSendStatusV2RequestTypeUnencoded.Factory.newInstance();
            for (String id : ids) {
                getSendStatusV2RequestTypeUnencoded.addNewItem().setDhlId(id);
            }
            log.debug("getSendStatus plain attachment body: '" + getSendStatusV2RequestTypeUnencoded + "'");
            GetSendStatusDocument.GetSendStatus getSendStatusRequest = GetSendStatusDocument.GetSendStatus.Factory.newInstance();
            GetSendStatusV2RequestType request = getSendStatusRequest.addNewKeha();
            Base64BinaryType dokumendid = request.addNewDokumendid();
            XmlCursor cursor = dokumendid.newCursor();
            String cid = AttachmentUtil.getUniqueCid();
            cursor.toNextToken();
            cursor.insertAttributeWithValue("href", "cid:" + cid);
            cursor.dispose();
            byte[] base64 = gzipAndEncodeXmlObjectNoXmlDecl(getSendStatusV2RequestTypeUnencoded);

            XRoadAttachment attachment = new XRoadAttachment(cid, "{http://www.w3.org/2001/XMLSchema}base64Binary", base64);
            XRoadMessage<GetSendStatusDocument.GetSendStatus> reqMessage = new XmlBeansXRoadMessage<GetSendStatusDocument.GetSendStatus>(getSendStatusRequest, Arrays.asList(attachment));

            log.debug("executing " + queryMethod);
            XRoadMessage<GetSendStatusV2ResponseTypeUnencoded> response = send(reqMessage, GET_SEND_STATUS, GET_SEND_STATUS_VERSION, null,
                    new GetSendStatusExtractor());

            if (response != null && response.getContent() != null) {
                return parseGetSendStatusItem(response.getContent().toString());
            }


        } catch (XRoadServiceConsumptionException e) {
            throw new WrappedXRoadServiceConsumptionException(e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute xtee query " + queryMethod, e);
        }
        return Collections.emptyMap();
    }

    private static class SendDocumentsExtractor extends CustomExtractor {

        public XRoadMessage<SendDocumentsV4ResponseTypeUnencoded> extractData(WebServiceMessage message) throws IOException, TransformerException {
            Iterator<Attachment> attachments = ((SaajSoapMessage) message).getAttachments();
            String xml = null;
            while(attachments.hasNext() && StringUtils.isBlank(xml)) {
                Attachment attachment = attachments.next();
                xml = new String(unzipAndDecode(attachment.getInputStream()), DVK_MESSAGE_CHARSET);
            }
            final SendDocumentsV4ResponseTypeUnencoded content = (StringUtils.isNotBlank(xml))?getTypeFromXml(addCorrectNamespaces(xml, null), SendDocumentsV4ResponseTypeUnencoded.class):null;
            return new XmlBeansXRoadMessage<SendDocumentsV4ResponseTypeUnencoded>(content);
        }

    }

    private static class ReceiveDocumentExtractor extends CustomExtractor {

        public XRoadMessage<ReceiveDocumentsV4ResponseTypeUnencoded> extractData(WebServiceMessage message) throws IOException, TransformerException {
            Iterator<Attachment> attachments = ((SaajSoapMessage) message).getAttachments();
            String xml = null;
            while(attachments.hasNext() && StringUtils.isBlank(xml)) {
                Attachment attachment = attachments.next();
                xml = new String(unzipAndDecode(attachment.getInputStream()), DVK_MESSAGE_CHARSET);
            }
            final ReceiveDocumentsV4ResponseTypeUnencoded content = (StringUtils.isNotBlank(xml))?getTypeFromXml(addCorrectNamespaces(xml, "dokumendid"), ReceiveDocumentsV4ResponseTypeUnencoded.class):null;
            return new XmlBeansXRoadMessage<ReceiveDocumentsV4ResponseTypeUnencoded>(content);
        }

    }

    private static class GetSendStatusExtractor extends CustomExtractor {

        public XRoadMessage<GetSendStatusV2ResponseTypeUnencoded> extractData(WebServiceMessage message) throws IOException, TransformerException {
            Attachment attachment = (Attachment) ((SaajSoapMessage) message).getAttachments().next();
            String xml = new String(unzipAndDecode(attachment.getInputStream()), DVK_MESSAGE_CHARSET);

            final GetSendStatusV2ResponseTypeUnencoded content = getTypeFromXml(xml, GetSendStatusV2ResponseTypeUnencoded.class);
            return new XmlBeansXRoadMessage<GetSendStatusV2ResponseTypeUnencoded>(content);
        }
/*
        public XRoadMessage<GetSendStatusV2ResponseTypeUnencoded> extractData(WebServiceMessage message) throws IOException, TransformerException {
            Attachment attachment = (Attachment) ((SaajSoapMessage) message).getAttachments().next();
            String xml = new String(unzipAndDecode(attachment.getInputStream()), DVK_MESSAGE_CHARSET);

            final GetSendStatusV2ResponseTypeUnencoded content = getTypeFromXml(addCorrectNamespaces(xml), GetSendStatusV2ResponseTypeUnencoded.class);
            return new XmlBeansXRoadMessage<GetSendStatusV2ResponseTypeUnencoded>(content);
        }


        private String addCorrectNamespaces(String xml) {
            Node root;
            try {
                DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document doc = docBuilder.parse(new InputSource(new StringReader(xml)));

                Document targetDoc = docBuilder.newDocument();
                root = targetDoc.appendChild(targetDoc.createElementNS("", "xml-fragment"));

                Node keha = doc.getFirstChild();
                NodeList items = keha.getChildNodes();
                for (int i = 0; i < items.getLength(); i++) {
                    Node item = items.item(i);
                    final String name1 = item.getNodeName();
                    log.debug("name=" + name1);
                    if ("item".equalsIgnoreCase(name1)) {
                        log.debug("parsing item");
                        Node itemTarget = root.appendChild(targetDoc.createElementNS("", "item"));

                        NodeList sublist = item.getChildNodes();
                        for (int j = 0; j < sublist.getLength(); j++) {
                            Node subItem = sublist.item(j);
                            final String localName = subItem.getNodeName();
                            if ("dhl_id".equalsIgnoreCase(localName)) {
                                final String NS_DHL_META_AUTOMATIC = "http://www.riik.ee/schemas/dhl-meta-automatic";
                                addNameSpace(NS_DHL_META_AUTOMATIC, subItem, itemTarget);
                            } else if ("edastus".equalsIgnoreCase(localName)) {
                                final String NS_SCHEMAS_DHL = "http://www.riik.ee/schemas/dhl";
                                addNameSpace(NS_SCHEMAS_DHL, subItem, itemTarget);
                            } else if ("olek".equalsIgnoreCase(localName)) {
                                addNameSpace("", subItem, itemTarget);
                                // } else {
                                // log.error("Unexpected localName: '"+localName+"', text:\n'"+subItem.getTextContent()+"'");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("failed to add correct namespaces to given xml: " + xml, e);
            }
            String resultXml = xmlNodeToString(root);
            if (log.isDebugEnabled()) {
                log.debug("Added namespaces for the xml.\n START: xml source:\n" + xml + "\n\nEND: xml source\nSTART: xml result\n" + resultXml
                        + "\n\nEND: xml result");
            }
            return resultXml;
        }

        private static String xmlNodeToString(Node node) {
            try {
                Source source = new DOMSource(node);
                StringWriter stringWriter = new StringWriter();
                Result result = new StreamResult(stringWriter);
                TransformerFactory factory = TransformerFactory.newInstance();
                Transformer transformer = factory.newTransformer();
                transformer.transform(source, result);
                return stringWriter.getBuffer().toString();
            } catch (TransformerConfigurationException e) {
                e.printStackTrace();
            } catch (TransformerException e) {
                e.printStackTrace();
            }
            return null;
        }

        private void addNameSpace(String ns, Node source, Node target) {
            Node newElement;
            if (source.getNodeType() == Node.TEXT_NODE) {
                final String textContent = source.getTextContent();
                log.debug("Creating textnode with content:\n'" + textContent + "'");
                newElement = target.getOwnerDocument().createTextNode(textContent);
            } else {
                newElement = target.getOwnerDocument().createElementNS(ns, source.getNodeName());
            }
            NodeList childNodes = source.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node childItem = childNodes.item(i);
                addNameSpace(ns, childItem, newElement);
            }
            target.appendChild(newElement);
        }
*/

    }

    /**
     * @author ats.uiboupin
     *         Since the list of DVK capable organisations grows/changes very rarely (maybe one in a month or even more rarely)
     *         it might be feasible to keep the list cached in memory and for example <br>
     *         updated onece a day by some job, <br>
     *         or use strategy pattern ant set strategy when creating instance of the service class in the top-level class.
     */
    private class GetDvkOrganizationsHelperImpl implements GetDvkOrganizationsHelper {
        private Map<String/* regNr */, String/* name */> dvkCapableOrganizations;

        private DvkOrganizationsUpdateStrategy updateStrategy;

        public Map<String/* regNr */, String/* name */> getDvkOrganizationsCache() {
            if (getUpdateStrategy().update4getDvkOrganizationsCache(dvkCapableOrganizations)) {
                updateDvkCapableOrganisationsCache();
            }
            return dvkCapableOrganizations;
        }

        public String getOrganizationName(String regnr) {
            String orgName = getDvkOrganizationsCache().get(regnr);
            if (getUpdateStrategy().update4getOrganizationName(orgName)) {
                updateDvkCapableOrganisationsCache();
                orgName = getDvkOrganizationsCache().get(regnr);
            }
            return orgName;
        }

        public void updateDvkCapableOrganisationsCache() {
            log.info("starting to update dvkCapableOrganisationsCache");
            setDvkOrganizationsCache(getSendingOptions());
            log.info("updated dvkCapableOrganisationsCache");
        }

        public void setDvkOrganizationsCache(Map<String, String> cache) {
            this.dvkCapableOrganizations = cache;
            getUpdateStrategy().setLastUpdated(Calendar.getInstance());
        }

        // START: getters/setters
        public DvkOrganizationsUpdateStrategy getUpdateStrategy() {
            if (updateStrategy == null) {
                log.debug("-- using default cache update strategy--");
                updateStrategy = new DhlXTeeService.DvkOrganizationsCacheingUpdateStrategy().setMaxUpdateInterval(24 * 60);// 24h
            }
            return updateStrategy;
        }

        public void setUpdateStrategy(DvkOrganizationsUpdateStrategy updateStrategy) {
            this.updateStrategy = updateStrategy;
        }
        // END: getters/setters

    }

    /**
     * @author ats.uiboupin
     *         Contains methods used by sendDocuments service. <br>
     *         Current implementation uses {@link GetDvkOrganizationsHelper} to fill receivers names based on registration codes.
     */
    private class SendDocumentsHelper {

        /**
         * Creates (Dhl)&lt;Dokument/&gt; element containing info about sender, recipients
         * and given files in &lt;SignedDoc/&gt; element(aka digiDoc)
         * 
         * @param contentsToSend
         * @param sender
         * @param recipients
         * @return (Dhl)&lt;Dokument/&gt; XmlObject
         */
        private DokumentDocument constructDokumentDocument(Collection<ContentToSend> contentsToSend, AadressType sender, AadressType[] recipients) {
            DokumentDocument dokumentDocument = DokumentDocument.Factory.newInstance();
            DhlDokumentType dokumentContainer = dokumentDocument.addNewDokument();

            // Add mandatory empty elements
            dokumentContainer.addNewMetaxml();
            dokumentContainer.addNewAjalugu();

            if (StringUtils.isBlank(sender.getAsutuseNimi())) {
                String senderName = getDvkOrganizationsHelper().getOrganizationName(sender.getRegnr());
                sender.setAsutuseNimi(senderName);
                log.debug("Added senders name based on organization code from DVK organizations list: '" + senderName + "'");
            }

            Transport transport = dokumentContainer.addNewTransport();
            transport.setSaatja(sender);

            for (AadressType recipient : recipients) {
                String recipientName = getDvkOrganizationsHelper().getOrganizationName(recipient.getRegnr());
                if (StringUtils.isBlank(recipientName)) {
                    throw new IllegalArgumentException("Cannot send documents to recipient with reg.Nr "
                            + recipient.getRegnr() + " using DVK because recipient" + recipient.getRegnr() + " is not DVK capable.");
                }
                recipient.setAsutuseNimi(recipientName);
            }
            transport.setSaajaArray(recipients);
            final DataFileType[] dataFiles = addSignedDocToDokument(contentsToSend, dokumentContainer);

            if (log.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("Constructed dokument from: ")//
                        .append(sender.getRegnr()).append("-").append(sender.getAsutuseNimi()).append(" to [");//
                for (AadressType recipient : recipients) {
                    sb.append(recipient.getRegnr()).append("-").append(recipient.getAsutuseNimi()).append(" | ");
                }
                sb.append("] with files: ");
                for (DataFileType dataFile : dataFiles) {
                    sb.append(dataFile.getFilename()).append("(").append(dataFile.getSize()).append(") | ");
                }
                log.debug(sb.toString());
            }
            return dokumentDocument;
        }

        /**
         * Adds &lt;SignedDoc/&gt; element(aka digiDoc) containing given files as &lt;DataFile/&gt; to
         * (Dhl)&lt;Dokument/&gt; element
         * 
         * @param contentsToSend - Object with InputStream and name of the file to be used for output and corresponding mimeType
         * @param dokumentContainer - (Dhl)&lt;Dokument/&gt; element to which &lt;SignedDoc/&gt; should be added as a child
         *            node
         * @return {DataFileType[]} based on input files
         */
        private DataFileType[] addSignedDocToDokument(Collection<ContentToSend> contentsToSend, DhlDokumentType dokumentContainer) {
            final DataFileType[] dataFiles = getDataFiles(contentsToSend);
            SignedDocType signedDoc = dokumentContainer.addNewSignedDoc();
            signedDoc.setFormat("DIGIDOC-XML");
            signedDoc.setVersion("1.3");
            signedDoc.setDataFileArray(dataFiles);
            return dataFiles;
        }

        /**
         * @param contentsToSend - Object with InputStream and name of the file to be used for output and corresponding mimeType
         * @return array of &lt;DataFile &gt; elements to be included inside the digiDoc envelope called &lt;SignedDoc /&gt;
         */
        private DataFileType[] getDataFiles(Collection<ContentToSend> contentsToSend) {
            DataFileType[] files = new DataFileType[contentsToSend.size()];
            int fileIndex = 0;
            for (ContentToSend contentToSend : contentsToSend) {
                DataFileType dataFile = DataFileType.Factory.newInstance();
                dataFile.setFilename(contentToSend.getFileName());
                dataFile.setId("D" + fileIndex);// spec: Andmefailide tunnused algavad sümboliga 'D', millele järgneb faili järjekorranumber
                dataFile.setMimeType(contentToSend.getMimeType());
                dataFile.setContentType(DataFileType.ContentType.EMBEDDED_BASE_64);

                final StringWriter stringWriter = new StringWriter();
                final OutputStream encodeStream = Base64.newEncoder(stringWriter, 0, null);
                final InputStream is = contentToSend.getInputStream();
                try {
                    long sizeCopied = IOUtils.copyLarge(is, encodeStream);
                    dataFile.setSize(BigDecimal.valueOf(sizeCopied));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to get input to the file to be sent", e);
                } finally {
                    IOUtils.closeQuietly(encodeStream);
                    IOUtils.closeQuietly(is);
                }
                dataFile.setStringValue(stringWriter.toString());
                files[fileIndex++] = dataFile;
            }
            return files;
        }

        /**
         * Creates (Dhl)&lt;DecContainer/&gt; element containing info about sender, recipients
         * and given files in &lt;SignedDoc/&gt; element(aka digiDoc)
         *
         * @param contentsToSend
         * @param sender
         * @param recipients
         * @return (Dhl)&lt;DecContainer/&gt; XmlObject
         */
        private DecContainerDocument constructDecContainerDocument(Collection<ContentToSend> contentsToSend, DecContainerDocument.DecContainer.Transport.DecSender sender, DecContainerDocument.DecContainer.Transport.DecRecipient[] recipients) {
            DecContainerDocument decContainerDocument = DecContainerDocument.Factory.newInstance();
            DecContainerDocument.DecContainer decContainer = decContainerDocument.addNewDecContainer();

            DecContainerDocument.DecContainer.Transport transport = decContainer.addNewTransport();
            transport.setDecSender(sender);

            for (DecContainerDocument.DecContainer.Transport.DecRecipient recipient : recipients) {
                if (StringUtils.isBlank(recipient.getPersonalIdCode())) {
                    String organisationCode = recipient.getOrganisationCode();
                    String recipientName = getDvkOrganizationsHelper().getOrganizationName(organisationCode);
                    if (StringUtils.isBlank(recipientName)) {
                        throw new IllegalArgumentException("Cannot send documents to recipient with reg.Nr "
                                + organisationCode + " using DVK because recipient" + organisationCode + " is not DVK capable.");
                    }
                }
            }
            transport.setDecRecipientArray(recipients);
            final DecContainerDocument.DecContainer.File[] dataFiles = addFilesToDecContainer(contentsToSend, decContainer);

            if (log.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("Constructed decContainer from: ")//
                        .append(sender.getOrganisationCode()).append("-").append(sender.getStructuralUnit()).append(" to [");//
                for (DecContainerDocument.DecContainer.Transport.DecRecipient recipient : recipients) {
                    String idCode = recipient.getPersonalIdCode();
                    if (StringUtils.isBlank(idCode)) {
                        sb.append("Organization: " + recipient.getOrganisationCode()).append("-").append(recipient.getStructuralUnit()).append(" | ");
                    } else {
                        sb.append("Person: " + recipient.getPersonalIdCode() + " | ");
                    }
                }
                sb.append("] with files: ");
                for (DecContainerDocument.DecContainer.File dataFile : dataFiles) {
                    sb.append(dataFile.getFileName()).append("(").append(dataFile.getFileSize()).append(") | ");
                }
                log.debug(sb.toString());
            }
            return decContainerDocument;
        }

        /**
         * Adds &lt;SignedDoc/&gt; element(aka digiDoc) containing given files as &lt;DataFile/&gt; to
         * (Dhl)&lt;Dokument/&gt; element
         *
         * @param contentsToSend - Object with InputStream and name of the file to be used for output and corresponding mimeType
         * @param decContainer - (Dhl)&lt;Dokument/&gt; element to which &lt;SignedDoc/&gt; should be added as a child
         *            node
         * @return dataFiles based on input files
         */
        private DecContainerDocument.DecContainer.File[] addFilesToDecContainer(Collection<ContentToSend> contentsToSend, DecContainerDocument.DecContainer decContainer) {
            final DecContainerDocument.DecContainer.File[] dataFiles = getV2DataFiles(contentsToSend);
            decContainer.setFileArray(dataFiles);
            return dataFiles;
        }

        /**
         * @param contentsToSend - Object with InputStream and name of the file to be used for output and corresponding mimeType
         * @return array of &lt;File &gt; elements to be included inside the &lt;Files /&gt; element
         */
        private DecContainerDocument.DecContainer.File[] getV2DataFiles(Collection<DhlXTeeService.ContentToSend> contentsToSend) {
            DecContainerDocument.DecContainer.File[] files = new DecContainerDocument.DecContainer.File[contentsToSend.size()];
            int fileIndex = 0;
            for (DhlXTeeService.ContentToSend contentToSend : contentsToSend) {
                DecContainerDocument.DecContainer.File dataFile = DecContainerDocument.DecContainer.File.Factory.newInstance();
                dataFile.setFileName(contentToSend.getFileName());
                dataFile.setFileGuid(contentToSend.getId());
                dataFile.setMimeType(contentToSend.getMimeType());

                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                final InputStream input = contentToSend.getInputStream();
                GZIPOutputStream zipStream = null;
                try {
                    zipStream = new GZIPOutputStream(new Base64OutputStream(output));
                    long sizeCopied = IOUtils.copyLarge(input, zipStream);
                    dataFile.setFileSize(BigInteger.valueOf(sizeCopied));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to get input to the file to be sent", e);
                } finally {
                    IOUtils.closeQuietly(zipStream); // Flush the contents to encoder
                    IOUtils.closeQuietly(input);
                }

                try {
                    dataFile.setZipBase64Content(output.toString("UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("Failed to get file contents as a string!", e);
                }
                files[fileIndex++] = dataFile;
            }
            return files;
        }


        /**
         * Writes the document into given stream. Removes namespace usage from SignedDoc element (Digidoc container) Amphora test
         * environment is not capable of receiving such xml
         */
        private void writeXmlObject(XmlObject xmlObject, OutputStream outputStream) {
            XmlOptions options = new XmlOptions();
            options.setCharacterEncoding(DVK_MESSAGE_CHARSET);
            { // fix DigiDoc client bug (also present with PostiPoiss doc-management-system)
              // they don't accept that SignedDoc have nameSpace alias set
                options.setSavePrettyPrint();
                HashMap<String, String> suggestedPrefixes = new HashMap<String, String>(2);
                suggestedPrefixes.put("http://www.sk.ee/DigiDoc/v1.3.0#", "");
                // suggestedPrefixes.put("http://www.sk.ee/DigiDoc/v1.4.0#", "");
                options.setSaveSuggestedPrefixes(suggestedPrefixes);
            }
            try {
                xmlObject.save(outputStream, options);
                writeXmlObjectToSentDocumentsFolder(xmlObject, options);
            } catch (IOException e) {
                log.error("Writing document failed", e);
                throw new java.lang.RuntimeException(e);
            }
        }

        private void writeXmlObjectToSentDocumentsFolder(XmlObject xmlObject, XmlOptions options) throws FileNotFoundException, IOException {
            OutputStream dvkSentMsgOS = null;
            try {
                if (StringUtils.isNotBlank(sentDocumentsFolder)) {
                    final File directory = new File(sentDocumentsFolder);
                    if (!directory.exists()) {
                        throw new FileNotFoundException("receivedDocumentsFolder '" + sentDocumentsFolder + "' doesn't exist!");
                    }
                    String newFilePrefix = "dvk_" + (new SimpleDateFormat("yyyy.MM.dd-kk.mm.ss,SSS").format(new Date()));
                    File sentFile = new File(directory, "sent_" + newFilePrefix + ".xml");
                    dvkSentMsgOS = new FileOutputStream(sentFile);
                    xmlObject.save(dvkSentMsgOS, options);
                }
            } finally {
                IOUtils.closeQuietly(dvkSentMsgOS);
            }
        }
    }

    private byte[] createMarkDocumentsReceivedAttachmentBody(List<String> ids) {
        MarkDocumentsReceivedV2RequestTypeUnencoded body = MarkDocumentsReceivedV2RequestTypeUnencoded.Factory.newInstance();
        TagasisideArrayType documentRefsArray = TagasisideArrayType.Factory.newInstance();
        for (String id : ids) {
            TagasisideArrayType.Item item = documentRefsArray.addNewItem();
            item.setDhlId(new BigInteger(id));
        }
        body.setDokumendid(documentRefsArray);
        if (log.isTraceEnabled()) {
            log.trace("createMarkDocumentsReceivedAttachmentBody::" + body);
        }
        return gzipAndEncodeString(body.toString());
    }

    private byte[] createMarkDocumentsReceivedV2AttachmentBody(Collection<TagasisideType> receivedDocsInfos) {
        List<TagasisideArrayType.Item> items = new ArrayList<TagasisideArrayType.Item>();
        for (TagasisideType tagasisideType: (ArrayList<TagasisideType>)receivedDocsInfos) {
            TagasisideArrayType.Item item = TagasisideArrayType.Item.Factory.newInstance();
            item.setDhlId(tagasisideType.getDhlId());
            items.add(item);
        }
        if (log.isTraceEnabled()) {
            log.trace("created markDocumentsReceivedV2 attachmentBody: " + items);
        }

        return gzipAndEncodeXmlObjectNoXmlDecl(items);
    }

    private byte[] gzipAndEncodeXmlObjectNoXmlDecl(XmlObject xmlObject) {
        XmlOptions options = new XmlOptions();
        options.setCharacterEncoding(DVK_MESSAGE_CHARSET);
        { // When marking documents received, with version 2
            // they don't accept that item children, such as dhl_id and fault have nameSpace prefixes set
            HashMap<String, String> suggestedPrefixes = new HashMap<String, String>(2);
            suggestedPrefixes.put("http://www.riik.ee/schemas/dhl", "");
            options.setSaveSuggestedPrefixes(suggestedPrefixes);
            options.setSaveNoXmlDecl();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            xmlObject.save(bos, options);
            String s = new String(bos.toByteArray(), DVK_MESSAGE_CHARSET);
            return gzipAndEncodeString(s);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create request attachmentBody", e);
        }
    }

    private byte[] gzipAndEncodeXmlObjectNoXmlDecl(List<TagasisideArrayType.Item> xmlObjects) {
        XmlOptions options = new XmlOptions();
        options.setCharacterEncoding(DVK_MESSAGE_CHARSET);
        {   // When marking documents received, with version 2
            // they don't accept that item children, such as dhl_id and fault have nameSpace prefixes set
            HashMap<String, String> suggestedPrefixes = new HashMap<String, String>(2);
            suggestedPrefixes.put("http://www.riik.ee/schemas/dhl", "");
            options.setSaveSuggestedPrefixes(suggestedPrefixes);
            options.setSaveNoXmlDecl();
        }
        try {
            String items = "";
            for (XmlObject xmlObject : xmlObjects) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                xmlObject.save(bos, options);
                String s = "<item>" + new String(bos.toByteArray(), DVK_MESSAGE_CHARSET) + "</item>";
                items = items + s;
            }
            return gzipAndEncodeString(items);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create request attachmentBody", e);
        }
    }

    private XRoadAttachment setDokumendidHrefToAttachment(byte[] base64Attachment, Base64BinaryType dokumendid) {
        final String cid = AttachmentUtil.getUniqueCid();
        final XRoadAttachment attachment = new XRoadAttachment(cid, "{http://www.w3.org/2001/XMLSchema}base64Binary", base64Attachment);
        dokumendid.setHref("cid:" + cid);
        return attachment;
    }

    private XRoadAttachment setDokumendidHrefToAttachment(byte[] base64Attachment, XmlObject request) {
        final String cid = AttachmentUtil.getUniqueCid();
        final XRoadAttachment attachment = new XRoadAttachment(cid, "{http://www.w3.org/2001/XMLSchema}base64Binary", base64Attachment);

        XmlCursor cursor = request.newCursor();
        cursor.toNextToken();
        Element node = (Element) cursor.getDomNode();
        node.setAttribute("href", "cid:" + cid);
        cursor.dispose();
        return attachment;
    }

    /**
     * @param <T> instance of this class will be returned if parsing <code>inputXml</code> is successful.
     * @param inputXml string representing xml
     * @param responseClass class of returnable instance
     * @return instance of given class T that extends XmlObject, parsed from <code>inputXml</code>
     */
    public static <T extends XmlObject> T getTypeFromXml(String inputXml, Class<T> responseClass) {
        try {
            SchemaType sType = (SchemaType) responseClass.getField("type").get(null);
            if (log.isTraceEnabled()) {
                log.trace("Starting to parse '" + inputXml + "' to class: " + responseClass.getCanonicalName());
            }
            XmlOptions replaceRootNameOpts = new XmlOptions().setLoadReplaceDocumentElement(new QName("xml-fragment"));
            final String xmlFragment = XmlObject.Factory.parse(inputXml, replaceRootNameOpts).toString();
            @SuppressWarnings("unchecked")
            T result = (T) XmlObject.Factory.parse(inputXml, new XmlOptions().setDocumentType(sType));
            return result;
        } catch (XmlException e) {
            throw new RuntimeException("Failed to parse '" + inputXml + "' to class: " + responseClass.getCanonicalName(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) { // if above exceptions were not caught it must be because of bad class
            throw new IllegalArgumentException("Failed to get value of '" + responseClass.getCanonicalName()
                    + ".type' to get corresponding SchemaType object: ", e);
        }
    }

    private static <T> List<T> getTypeFromGzippedAndEncodedSoapArray(InputStream inputStream, Class<T> responseClass) {
        SchemaType unencodedType = null;
        try {
            unencodedType = (SchemaType) responseClass.getField("type").get(null);
            log.debug("unencodedType=" + unencodedType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get value of '" + responseClass.getCanonicalName() + ".type' to get corresponding SchemaType object: ", e);
        }
        String responseString;
        try {
            responseString = new String(unzipAndDecode(inputStream), DVK_MESSAGE_CHARSET);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Failed to encode responseString to " + DVK_MESSAGE_CHARSET, e);
        }
        if (StringUtils.isBlank(responseString)) {
            return Collections.emptyList();
        }
        ArrayList<T> result = null;
        try {
            responseString = "<root>" + responseString + "</root>";
            XmlOptions options = new XmlOptions();
            if (log.isTraceEnabled()) {
                log.trace("Starting to parse '" + responseString + "' to class: " + responseClass.getCanonicalName() + "\n\n");
            }
            // XXX: potential optimization idea that could allow us to receive twice as big files as we can receive now:
            // use following line to create xmlObject,
            // but to avoid failure we must prepend to original inputStream some xml start element and append corresponding end element
            // but since rest of the inputStream is zipped and encoded to base64, then prepended and appended root element must also be zipped and encoded
            // XmlObject xmlObject = XmlObject.Factory.parse(unzip(decodeFromBase64(inputStream)), options);
            XmlObject xmlObject = XmlObject.Factory.parse(responseString, options);
            XmlCursor cursor = xmlObject.newCursor();
            cursor.toFirstChild();
            cursor.toFirstChild();
            options.setDocumentType(unencodedType);

            result = new ArrayList<T>();
            int i = 0;
            do {
                if (log.isTraceEnabled()) {
                    cursor.getObject();
                    log.trace("Type of token " + (i++) + ": '" + cursor.currentTokenType() + "'");
                }
                @SuppressWarnings("unchecked")
                T resultItem = (T) XmlObject.Factory.parse(cursor.getDomNode(), options);
                result.add(resultItem);
            } while (cursor.toNextSibling());
            cursor.dispose();
        } catch (XmlException e) {
            throw new RuntimeException("Failed to parse '" + responseString + "' to class: " + responseClass.getCanonicalName(), e);
        }
        return result;
    }

    private byte[] gzipAndEncodeXmlObject(XmlObject xmlObject) {
        OutputStream gzipBase64OutputStream = null;
        ByteArrayOutputStream gzippedAndEncodedOutputStream = new ByteArrayOutputStream();
        Writer writer = null;
        try {
            writer = createOutputStreamWriter(gzippedAndEncodedOutputStream);
            gzipBase64OutputStream = createGzipBase64OutputStream(writer);
            sendDocumentsHelper.writeXmlObject(xmlObject, gzipBase64OutputStream);
        } catch (IOException e1) {
            throw new RuntimeException("Failed to encode input", e1);
        } finally {
            IOUtils.closeQuietly(gzipBase64OutputStream);
            IOUtils.closeQuietly(writer);
        }
        return gzippedAndEncodedOutputStream.toByteArray();
    }

    private byte[] gzipAndEncodeString(String inputString) {
        OutputStream gzipBase64OutputStream = null;
        ByteArrayOutputStream gzippedAndEncodedOutputStream = new ByteArrayOutputStream();
        Writer writer = null;
        try {
            writer = createOutputStreamWriter(gzippedAndEncodedOutputStream);
            gzipBase64OutputStream = createGzipBase64OutputStream(writer);
            IOUtils.write(inputString, gzipBase64OutputStream, DVK_MESSAGE_CHARSET);
        } catch (IOException e1) {
            throw new RuntimeException("Failed to encode input", e1);
        } finally {
            IOUtils.closeQuietly(gzipBase64OutputStream);
            IOUtils.closeQuietly(writer);
        }
        return gzippedAndEncodedOutputStream.toByteArray();
    }

    private OutputStream createGzipBase64OutputStream(
            Writer writer)
            throws UnsupportedEncodingException, IOException {
        OutputStream base64OutputStream = Base64.newEncoder(writer, 0, null);
        OutputStream gzipOutputStream = new GZIPOutputStream(base64OutputStream);
        return gzipOutputStream;
    }

    private Writer createOutputStreamWriter(ByteArrayOutputStream gzippedAndEncodedOutputStream)
            throws UnsupportedEncodingException {
        return new BufferedWriter(new OutputStreamWriter(gzippedAndEncodedOutputStream, DVK_MESSAGE_CHARSET));
    }

    /**
     * @param inputStream
     * @return plain content from transformed content: <code>base64Encode(gzip(content))</code>
     */
    private static byte[] unzipAndDecode(InputStream inputStream) {
        return getDecodeAndUnzipOutputStream(inputStream, new ByteArrayOutputStream()).toByteArray();
    }

    /**
     * @param inputStream
     * @return plain content from transformed content: <code>base64Encode(gzip(content))</code>
     */
    private static byte[] unzipAndDecodeSimple(InputStream inputStream) {

        return getDecodeAndUnzipOutputStream(inputStream, new ByteArrayOutputStream()).toByteArray();
    }

    private static byte [] getUnzipAndDecodeOutputStream1(InputStream inputStream, ByteArrayOutputStream outputStream) {
        byte [] result = null;
        GZIPInputStream gzipInputStream = null;
        Writer decoderWriter = null;
        try {



            System.out.println("1 = " + new String(outputStream.toByteArray(), DVK_MESSAGE_CHARSET));
            //decoderWriter = Base64.newDecoder(outputStream);
            String input = new String("VMO1ZWxpbmUgdGFsdiB2w7VpYiBzYWFidWRhIGhpbGplbSBrdWkgdGF2YWxpc2VsdC4K");
            byte [] decoded = Base64.decode(input);
            IOUtils.copy(new ByteArrayInputStream(decoded), outputStream);
            //decoderWriter.flush();
            System.out.println("1.1 = " + new String(decoded, DVK_MESSAGE_CHARSET));
            System.out.println("1.2 = " + new String(decoded));
            outputStream.flush();
            System.out.println("2 = " + new String(outputStream.toByteArray(), DVK_MESSAGE_CHARSET));
            gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(outputStream.toByteArray()));
            ByteArrayOutputStream outputStreamRes = new ByteArrayOutputStream();
            IOUtils.copy(gzipInputStream, outputStreamRes);
            outputStreamRes.flush();
            result = outputStreamRes.toByteArray();
        } catch (Throwable e) {
            throw new RuntimeException("failed to unzip and decode input", e);
        } finally {
            IOUtils.closeQuietly(decoderWriter);
            IOUtils.closeQuietly(outputStream);
            IOUtils.closeQuietly(gzipInputStream);
            IOUtils.closeQuietly(inputStream);

        }

        return result;
    }


    private String addCorrectNamespaces(String xml) {
        Node root;
        try {
            DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = docBuilder.parse(new InputSource(new StringReader(xml)));

            Document targetDoc = docBuilder.newDocument();
            root = targetDoc.appendChild(targetDoc.createElementNS("", "xml-fragment"));

            Node keha = doc.getFirstChild();
            NodeList items = keha.getChildNodes();
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                final String name1 = item.getNodeName();
                log.debug("name=" + name1);
                if ("item".equalsIgnoreCase(name1)) {
                    log.debug("parsing item");
                    Node itemTarget = root.appendChild(targetDoc.createElementNS("", "item"));

                    NodeList sublist = item.getChildNodes();
                    for (int j = 0; j < sublist.getLength(); j++) {
                        Node subItem = sublist.item(j);
                        final String localName = subItem.getNodeName();
                        if ("dhl_id".equalsIgnoreCase(localName)) {
                            final String NS_DHL_META_AUTOMATIC = "http://www.riik.ee/schemas/dhl-meta-automatic";
                            addNameSpace(NS_DHL_META_AUTOMATIC, subItem, itemTarget);
                        } else if ("edastus".equalsIgnoreCase(localName)) {
                            final String NS_SCHEMAS_DHL = "http://www.riik.ee/schemas/dhl";
                            addNameSpace(NS_SCHEMAS_DHL, subItem, itemTarget);
                        } else if ("olek".equalsIgnoreCase(localName)) {
                            addNameSpace("", subItem, itemTarget);
                            // } else {
                            // log.error("Unexpected localName: '"+localName+"', text:\n'"+subItem.getTextContent()+"'");
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to add correct namespaces to given xml: " + xml, e);
        }
        String resultXml = xmlNodeToString(root);
        if (log.isDebugEnabled()) {
            log.debug("Added namespaces for the xml.\n START: xml source:\n" + xml + "\n\nEND: xml source\nSTART: xml result\n" + resultXml
                    + "\n\nEND: xml result");
        }
        return resultXml;
    }

    private static String xmlNodeToString(Node node) {
        try {
            Source source = new DOMSource(node);
            StringWriter stringWriter = new StringWriter();
            Result result = new StreamResult(stringWriter);
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            transformer.transform(source, result);
            return stringWriter.getBuffer().toString();
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void addNameSpace(String ns, Node source, Node target) {
        Node newElement;
        if (source.getNodeType() == Node.TEXT_NODE) {
            final String textContent = source.getTextContent();
            log.debug("Creating textnode with content:\n'" + textContent + "'");
            newElement = target.getOwnerDocument().createTextNode(textContent);
        } else {
            newElement = target.getOwnerDocument().createElementNS(ns, source.getNodeName());
        }

        NodeList childNodes = source.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childItem = childNodes.item(i);
            addNameSpace(ns, childItem, newElement);
        }
        target.appendChild(newElement);
    }

    public static void main (String [] args) {
        try {
            DhlXTeeServiceImpl dhlserv = new DhlXTeeServiceImpl();

            String xml = "<keha><item><dhl_id>54563</dhl_id><edastus><saaja><regnr>70008440</regnr></saaja><saadud>2017-10-06T11:04:45+03:00</saadud><meetod>xtee</meetod><edastatud>2017-10-06T11:04:45+03:00</edastatud><loetud>2017-10-06T11:05:44+03:00</loetud><staatus>saadetud</staatus><vastuvotja_staatus_id>0</vastuvotja_staatus_id></edastus><olek>saadetud</olek></item></keha>";

            /*
            final GetSendStatusV2ResponseTypeUnencoded content = getTypeFromXml(xml, GetSendStatusV2ResponseTypeUnencoded.class);

            System.out.println("content = " + content);
            Map<String, List<Map<String, Serializable>>> items = dhlserv.parseGetSendStatusItem(content.toString());
            String dhlId = content.getItemList().get(0).getDhlId();
            System.out.println("dhlId =" + dhlId);
            System.out.println("olek =" + content.getItemList().get(0).getOlek());
            List<EdastusDocument.Edastus> forwardings = content.getItemList().get(0).getEdastusList();
            for (EdastusDocument.Edastus forwarding : forwardings) {
                Calendar read = forwarding.getLoetud();
                Date receiveTime = read != null ? read.getTime() : null;
                System.out.println("status = " + forwarding.getStaatus() + " time="  + receiveTime);
                System.out.println("edastus = " + forwarding);

            }
            //System.out.println("item" + content.getItemList());
*/
            String input = new String("H4sIAAAAAAAAALOxr8jNUShLLSrOzM+zVTLUM1BSSM1Lzk/JzEu3VQoNcdO1ULK347LJLEnNteNSULBJyciJz0yxMzUxMzW20YfyuGz0wQoAyw51XU4AAAA=");
            xml = "";

            byte[] uncoded = Base64.decode(input);
            Writer writer = new StringWriter();
            GZIPInputStream unzip = null;
            try {
                unzip = new GZIPInputStream(new ByteArrayInputStream(uncoded));
                IOUtils.copy(unzip, writer);
            } finally {
                IOUtils.closeQuietly(unzip);
                IOUtils.closeQuietly(writer);
            }
            xml = writer.toString();
            System.out.println("xml = " + xml);

            /*
            try {
                StringBuilder sb = new StringBuilder();
                String line = "";
                //BufferedReader r = new BufferedReader(new FileReader("c:/Dev/tmp/receiveDocsTest.xml"));
                BufferedReader r = new BufferedReader(new FileReader("c:/Dev/tmp/sendDocsTest.txt"));
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                xml = sb.toString();
            } catch (Throwable t) {
                t.printStackTrace();
            }

            //DecContainerDocument.DecContainer receiveDocumentsV4ResponseTypeUnencoded = getTypeFromXml(addCorrectNamespaces(xml), DecContainerDocument.DecContainer.class);
            //String correctedXml = addCorrectNamespaces(xml);
            /*
            String correctedXml = xml;
            ReceiveDocumentsV4ResponseTypeUnencoded receiveDocumentsV4ResponseTypeUnencoded = getTypeFromXml(addCorrectNamespaces(xml, "dokumendid"), ReceiveDocumentsV4ResponseTypeUnencoded.class);
            ReceiveDocumentsV4ResponseTypeUnencoded.Dokumendid docs = receiveDocumentsV4ResponseTypeUnencoded.getDokumendid();
            for (DecContainerDocument.DecContainer decContainer : docs.getDecContainerList()) {
                if (decContainer == null) {
                    continue;
                }
                DecContainerDocument.DecContainer.DecMetadata decMetadata = decContainer.getDecMetadata();

                BigInteger dhlId = decMetadata.getDecId();
                System.out.println("dhlID = " + dhlId);
            }
            */
            //System.out.println("test = " + new String(Base64.decode(input),DVK_MESSAGE_CHARSET));
            //System.out.println("test = " + new String(getDecodeAndUnzipOutputStream(new ByteArrayInputStream(input.getBytes(DVK_MESSAGE_CHARSET)), new ByteArrayOutputStream()).toByteArray(), DVK_MESSAGE_CHARSET));
            //System.out.println("test = " + new String(getDecodeAndUnzipOutputStream(new ByteArrayInputStream(xml.getBytes(DVK_MESSAGE_CHARSET)), new ByteArrayOutputStream()).toByteArray(), DVK_MESSAGE_CHARSET));
            //System.out.println("test = " + new String(getUnzipAndDecodeOutputStream1(new ByteArrayInputStream(input.getBytes(DVK_MESSAGE_CHARSET)), new ByteArrayOutputStream()), DVK_MESSAGE_CHARSET));
            //System.out.println("test = " + new String(getDecodeAndUnzipOutputStream(new ByteArrayInputStream(input.getBytes(DVK_MESSAGE_CHARSET)), new ByteArrayOutputStream()).toByteArray(), DVK_MESSAGE_CHARSET));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static String addCorrectNamespaces(String xml, String rootTag) {
        StringBuilder resultXml = new StringBuilder();
        String xmlVersion =  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
        String xmlFragmentStart = "<xml-fragment>";
        String xmlFragmentEnd = "</xml-fragment>";
        resultXml.append(xmlVersion).append(xmlFragmentStart);
        if (StringUtils.isNotBlank(rootTag)) {
            resultXml.append("<").append(rootTag).append(">");
        }
        resultXml.append(xml);
        if (StringUtils.isNotBlank(rootTag)) {
            resultXml.append("</").append(rootTag).append(">");
        }
        resultXml.append(xmlFragmentEnd);
        if (log.isDebugEnabled()) {
            log.debug("Added namespaces for the xml.\n START: xml source:\n" + xml + "\n\nEND: xml source\nSTART: xml result\n" + resultXml
                    + "\n\nEND: xml result");
        }
        return resultXml.toString();
    }



    /**
     * @param <OS> OutputStream that is used as an output for <code>content</code> from the inputStream that was transformed
     *            <code>base64Encode(gzip(content))</code>
     * @param inputStream input to parse
     * @param outputStream destination class of the type to parse input
     * @return list of attachments parsed to given type
     */
    private static <OS extends OutputStream> OS getDecodeAndUnzipOutputStream(InputStream inputStream, final OS outputStream) {
        final PipedOutputStream pipedOutputStream = new PipedOutputStream();
        final List<Throwable> ungzipThreadThrowableList = new LinkedList<Throwable>();
        Writer decoderWriter = null;
        Thread ungzipThread = null;
        try {
            final PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream);
            ungzipThread = new Thread(new Runnable() {
                public void run() {
                    GZIPInputStream gzipInputStream = null;
                    try {
                        // (3) pipedInputStream'i käsitletakse zip'itud voona, mida gzipInputStream unzip'ib
                        gzipInputStream = new GZIPInputStream(pipedInputStream);
                        // (4) gzipInputStream edastab temasse kirjutatud zip'itud andmed byteArrayOutputStream'i, unzippides
                        IOUtils.copy(gzipInputStream, outputStream);
                    } catch (Throwable t) {
                        ungzipThreadThrowableList.add(t);
                    } finally {
                        IOUtils.closeQuietly(gzipInputStream);
                        IOUtils.closeQuietly(pipedInputStream);
                    }
                }
            });
            decoderWriter = Base64.newDecoder(pipedOutputStream);// (2) decoderWriter edastab temasse kirjutatu pipedInputStream'i, eemaldades base64 kodeeringu
            ungzipThread.start();
            IOUtils.copy(inputStream, decoderWriter, DVK_MESSAGE_CHARSET); // (1) - inputStream'ist base64 decoderWriter'isse kirjutamine,
            decoderWriter.flush();
            System.out.println("decoderWriter = " + decoderWriter.toString());
            pipedOutputStream.flush();

        } catch (IOException e) {
            throw new RuntimeException("failed to unzip and decode input", e);
        } finally {
            IOUtils.closeQuietly(decoderWriter);
            IOUtils.closeQuietly(pipedOutputStream);
            if (ungzipThread != null) {
                try {
                    ungzipThread.join();
                } catch (InterruptedException ie) {
                    throw new RuntimeException("thread interrupted while for ungzip thread to finish", ie);
                }
            }
        }
        if (!ungzipThreadThrowableList.isEmpty()) {
            throw new RuntimeException("ungzip failed", ungzipThreadThrowableList.get(0));
        }
        return outputStream;
    }

    // START: getters/setters
    public GetDvkOrganizationsHelper getDvkOrganizationsHelper() {
        return dvkOrganizationsHelper;
    }

    public void setDvkOrganizationsHelper(GetDvkOrganizationsHelper dvkOrganizationsHelper) {
        this.dvkOrganizationsHelper = dvkOrganizationsHelper;
    }

    public void setReceivedDocumentsResponseRootElemName(String receivedDocumentsResponseRootElemName) {
        this.receivedDocumentsResponseRootElemName = receivedDocumentsResponseRootElemName;
    }

    public void setReceivedDocumentsFolder(String receivedDocumentsFolder) {
        this.receivedDocumentsFolder = receivedDocumentsFolder;
    }

    public void setSentDocumentsFolder(String sentDocumentsFolder) {
        this.sentDocumentsFolder = sentDocumentsFolder;
    }

    public void setXRoadServiceConfigurationProvider(XRoadServiceConfigurationProvider xRoadServiceConfigurationProvider) {
        this.xRoadServiceConfigurationProvider = xRoadServiceConfigurationProvider;
    }

    public void setXRoadConsumer(XRoadConsumer xRoadConsumer) {
        this.xRoadConsumer = xRoadConsumer;
    }

  // END: getters/setters

    /**
     * Subclass of {@link InputStream} that will write everything read from wrapped inputStream to outputStream if outputStream is set - otherwise does nothing
     * more than delegates all method invocations to wrapped input stream. <br>
     * <br>
     * <b> NB! note that {@link #reset()} throws {@link UnsupportedOperationException} if outputStream is set and smth is already written there by this class
     * </b>(since write operation can't be taken back)
     * 
     * @author Ats Uiboupin
     */
    static class InputStreamWithOutputProxy extends ProxyInputStream {

        private OutputStream outputStream;
        private boolean smthWrittenToOut;

        public InputStreamWithOutputProxy(InputStream proxy) {
            super(proxy);
        }

        /**
         * @param outputStream - stream that will get written when proxied inputStream gets read
         */
        public void setOutputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (outputStream != null) {
                outputStream.close();
            }
        }

        @Override
        public int read() throws IOException {
            final int byteRead = super.read();
            if (outputStream != null && byteRead != -1) {
                outputStream.write(byteRead);
                smthWrittenToOut = true;
            }
            return byteRead;
        }

        @Override
        public int read(byte[] bts, int st, int end) throws IOException {
            final int len = super.read(bts, st, end);
            if (outputStream != null && len != -1) {
                outputStream.write(bts, 0, len);
                smthWrittenToOut = true;
            }
            return len;
        }

        @Override
        public int read(byte[] bts) throws IOException {
            final int len = super.read(bts);
            if (outputStream != null && len != -1) {
                outputStream.write(bts, 0, len);
                // outputStream.write(bts);
                smthWrittenToOut = true;
            }
            return len;
        }

        /**
         * XXX Calling mark() is supported if wrapped inputstream supports it, <br>
         * however reseting is not supported if outputstream is set (and smth is
         * already written to ouput stream by this class because of read method) <br>
         * <i>Inherited documentation:</i> <br>{@inheritDoc}
         */
        @Override
        public boolean markSupported() {
            return super.markSupported() && (outputStream == null || !smthWrittenToOut);
        }

        /**
         * <b>NB! note that this method throws {@link UnsupportedOperationException} if outputStream is set and smth is already written there by this class
         * </b>(since write operation can't be taken back),<br>
         * otherwise just resets wrapped inputStream as described bellow (in the documentation of superclass): <br>
         * <br>
         * <i>Inherited documentation:</i> <br>{@inheritDoc}
         */
        @Override
        public synchronized void reset() throws IOException, UnsupportedOperationException {
            super.reset();
            if (outputStream != null && smthWrittenToOut) {
                throw new UnsupportedOperationException("reseting inputstream is not supported w");
            }
        }


    }

    private Document getDocument(String response) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(response)));
        return doc;
    }

    private String extractElement(Element elements, String elementName) {
        Element element = (Element) elements.getElementsByTagName(elementName).item(0);
        return element == null ? null : element.getTextContent();
    }

    private Map<String, List<Map<String, Serializable>>> parseGetSendStatusItem(String response)
            throws IOException, ParserConfigurationException, SAXException {
        Document doc = getDocument(response);
        Map<String, List<Map<String, Serializable>>> result = new HashMap<String, List<Map<String, Serializable>>>();

        NodeList itemList = doc.getElementsByTagName("item");
        int nodes = itemList.getLength();
        log.debug("Found items: " + nodes);

        for (int i = 0; i < nodes; i++) {

            Element item = (Element) itemList.item(i);
            List<Map<String, Serializable>> edastuses = new ArrayList<Map<String, Serializable>>();
            NodeList edastusList = item.getElementsByTagName("edastus");
            log.debug("ITEM: " + i + "/" + nodes + ":: edastus block count: " + edastusList.getLength());
            for (int j = 0; j < edastusList.getLength(); j++) {
                Element edastus = (Element) edastusList.item(j);
                Map<String, Serializable> itemProps = new HashMap<String, Serializable>();
                extractProperties(itemProps, edastus);
                edastuses.add(itemProps);
            }


            String dhlId = extractElement(item, "dhl_id");
            String olek = extractElement(item, "olek");
            log.debug("dhl_id: " + dhlId + " :: olek: " + olek);

            result.put(dhlId, edastuses);
        }
        return result;
    }

    private void extractProperties(Map<String, Serializable> itemProps, Element node) {
        log.debug("Parse item edastus block...");
        NodeList props = node.getChildNodes();
        log.debug("Found edastus block fields: " + props.getLength());
        for (int j = 0; j < props.getLength(); j++) {
            if (!(props.item(j) instanceof Element)) {
                continue;
            }
            Element prop = (Element) props.item(j);
            String propName = prop.getTagName();
            if ("saaja".equals(propName)) {
                log.trace("Edastus field name: saaja :: parse subitem block...");
                extractProperties(itemProps, prop);
            } else {
                String value = prop.getTextContent();
                log.trace("Edastus field name: " + propName + " => value: " + prop.getTextContent());

                if (PROP_CLASS_MAPPING.containsKey(propName)) {
                    try {
                        Class<?> clazz = PROP_CLASS_MAPPING.get(propName);
                        if (Date.class.equals(clazz)) {
                            Date formatedDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(value);
                            itemProps.put(prop.getTagName(), formatedDate);
                        } else {
                            Constructor<?> constructor = clazz.getConstructor(String.class);
                            itemProps.put(prop.getTagName(), (Serializable) constructor.newInstance(new Object[] { value }));
                        }
                    } catch (Exception e) {
                        itemProps.put(prop.getTagName(), value);
                    }
                } else {
                    itemProps.put(prop.getTagName(), value);
                }
            }
        }
        log.debug("Parse item edastus block... DONE!");
    }

}
