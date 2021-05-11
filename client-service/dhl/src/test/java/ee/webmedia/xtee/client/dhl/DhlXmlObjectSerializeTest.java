package ee.webmedia.xtee.client.dhl;

import com.nortal.jroad.client.dhl.types.ee.riik.schemas.deccontainer.vers21.*;
import com.nortal.jroad.client.dhl.types.ee.riik.xrd.dhl.producers.producer.dhl.*;
import junit.framework.Assert;
import junit.framework.TestCase;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;

public class DhlXmlObjectSerializeTest extends TestCase {
  private String newLine = System.getProperty("line.separator");
  private DateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

  public void testGetSendingOptions() throws IOException {
    GetSendingOptionsDocument.GetSendingOptions sendingOptions = GetSendingOptionsDocument.GetSendingOptions.Factory.newInstance();
    sendingOptions.addNewKeha();

    String xml = serialize(sendingOptions, "getSendingOptions");

    String expected = "<ns5:getSendingOptions xmlns:ns5=\"http://producers.dhl.xrd.riik.ee/producer/dhl\">" + newLine +
        "  <keha/>" + newLine +
        "</ns5:getSendingOptions>";
    Assert.assertEquals(expected, xml);
  }

  public void testReceiveDocuments() throws IOException {
    ReceiveDocumentsDocument.ReceiveDocuments receiveDocuments =
        ReceiveDocumentsDocument.ReceiveDocuments.Factory.newInstance();
    ReceiveDocumentsV4RequestType keha = receiveDocuments.addNewKeha();
    keha.setArv(BigInteger.valueOf(5));

    String xml = serialize(receiveDocuments, "receiveDocuments");

    String expected = "<ns5:receiveDocuments xmlns:ns5=\"http://producers.dhl.xrd.riik.ee/producer/dhl\">" + newLine +
        "  <keha>" + newLine +
        "    <arv>5</arv>" + newLine +
        "  </keha>" + newLine +
        "</ns5:receiveDocuments>";
    Assert.assertEquals(expected, xml);
  }

  public void testMarkDocumentsReceived() throws IOException {
    MarkDocumentsReceivedDocument.MarkDocumentsReceived markDocumentsReceived =
        MarkDocumentsReceivedDocument.MarkDocumentsReceived.Factory.newInstance();
    MarkDocumentsReceivedV2RequestType keha = markDocumentsReceived.addNewKeha();
    keha.addNewDokumendid();

    XmlCursor cursor = keha.newCursor();
    cursor.toNextToken();
    Element node = (Element) cursor.getDomNode();
    node.setAttribute("href", "cid:6b68cb07-614f-4327-89ea-5e34be9b9ced1");
    cursor.dispose();

    String xml = serialize(markDocumentsReceived, "markDocumentsReceived");

    String expected = "<ns5:markDocumentsReceived xmlns:ns5=\"http://producers.dhl.xrd.riik.ee/producer/dhl\">" + newLine +
        "  <keha>" + newLine +
        "    <dokumendid xsi:nil=\"true\" href=\"cid:6b68cb07-614f-4327-89ea-5e34be9b9ced1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>" + newLine +
        "  </keha>" + newLine +
        "</ns5:markDocumentsReceived>";
    Assert.assertEquals(expected, xml);
  }

  public void testMarkDocumentsReceivedAttachment() throws IOException {
    TagasisideArrayType.Item item = TagasisideArrayType.Item.Factory.newInstance();
    item.setDhlId(BigInteger.valueOf(5));

    String xml = serializeItem(item);

    String expected = "<item>" + newLine +
        "  <dhl_id>5</dhl_id>" + newLine +
        "</item>";
    Assert.assertEquals(expected, xml);
  }

  public void testSendDocuments() throws IOException {
    SendDocumentsDocument sendDocumentsDocument =
        SendDocumentsDocument.Factory.newInstance();
    SendDocumentsDocument.SendDocuments sendDocuments =
        sendDocumentsDocument.addNewSendDocuments();
    SendDocumentsV4RequestType keha = sendDocuments.addNewKeha();
    Base64BinaryType dokumendid = keha.addNewDokumendid();
    dokumendid.setHref("cid:6b68cb07-614f-4327-89ea-5e34be9b9ced1");
    keha.setKaust("/");

    String xml = serialize(sendDocuments, "sendDocuments");

    String expected = "<ns5:sendDocuments xmlns:ns5=\"http://producers.dhl.xrd.riik.ee/producer/dhl\">" + newLine +
        "  <keha>" + newLine +
        "    <dokumendid href=\"cid:6b68cb07-614f-4327-89ea-5e34be9b9ced1\"/>" + newLine +
        "    <kaust>/</kaust>" + newLine +
        "  </keha>" + newLine +
        "</ns5:sendDocuments>";
    Assert.assertEquals(expected, xml);
  }

  public void testSendDocumentsAttachment() throws IOException, ParseException {
    DecContainerDocument decContainerDocument = DecContainerDocument.Factory.newInstance();
    DecContainerDocument.DecContainer decContainer = decContainerDocument.addNewDecContainer();
    //DecContainerDocument.DecContainer decContainer = DecContainerDocument.DecContainer.Factory.newInstance();

    //DecContainerDocument.DecContainer.Transport transport = DecContainerDocument.DecContainer.Transport.Factory.newInstance();
    DecContainerDocument.DecContainer.Transport transport = decContainer.addNewTransport();
    DecContainerDocument.DecContainer.Transport.DecSender decSender = transport.addNewDecSender();
    decSender.setOrganisationCode("10391131");
    DecContainerDocument.DecContainer.Transport.DecRecipient recipient = transport.addNewDecRecipient();
    recipient.setOrganisationCode("10391131");

    DecContainerDocument.DecContainer.RecordCreator recordCreator = decContainer.addNewRecordCreator();
    OrganisationType organization = recordCreator.addNewOrganisation();
    organization.setName("Nortal AS");
    organization.setOrganisationCode("10391131");
    PersonType person = recordCreator.addNewPerson();
    person.setName("Andres Traks");
    ContactDataType contactData = recordCreator.addNewContactData();
    contactData.setPhone("123");
    contactData.setEmail("andres.traks@nortal.com");

    DecContainerDocument.DecContainer.RecordSenderToDec recordSenderToDec = decContainer.addNewRecordSenderToDec();
    organization = recordSenderToDec.addNewOrganisation();
    organization.setName("Nortal AS");
    organization.setOrganisationCode("10391131");
    organization.setStructuralUnit("Kontor");
    organization.setPositionTitle("Arendaja");
    person = recordSenderToDec.addNewPerson();
    person.setName("Andres Traks");
    person.setPersonalIdCode("11111111111");
    contactData = recordSenderToDec.addNewContactData();
    contactData.setEmail("andres.traks@nortal.com");
    PostalAddressType postalAddress = contactData.addNewPostalAddress();
    postalAddress.setCountry("Eesti");
    postalAddress.setCounty("Tartu maakond");
    postalAddress.setLocalGovernment("Tartu");
    postalAddress.setAdministrativeUnit("Kesklinn");
    postalAddress.setStreet("Riia");
    postalAddress.setHouseNumber("1");
/*
    decContainer.addNewRecipient();

    decContainer.addNewRecordMetadata();

    decContainer.addNewAccess();
*/
    DecContainerDocument.DecContainer.SignatureMetadata signatureMetadata = decContainer.addNewSignatureMetadata();
    signatureMetadata.setSignatureType("Digitaalallkiri");
    signatureMetadata.setSigner("ANDRES TRAKS");
    signatureMetadata.setVerified("Allkiri on kehtiv");
    //signatureMetadata.setSignatureVerificationDate(getCalendar("23.04.2019"));

    DecContainerDocument.DecContainer.File file = decContainer.addNewFile();
    file.setFileGuid("b1b4732e-a710-4672-b0a0-38a4a56a4233");
    file.setFileName("test.txt");
    file.setMimeType("application/vnd.etsi.asic-e+zip");
    file.setFileSize(BigInteger.valueOf(10));
    file.setZipBase64Content("H4sIAAAAAAAAAABDQL");
    String xml = serializeDecContainerDocument(decContainerDocument);

    String expected = "<vers:DecContainer xmlns:vers=\"http://www.riik.ee/schemas/deccontainer/vers_2_1/\">" + newLine +
        "  <vers:Transport>" + newLine +
        "    <vers:DecSender>" + newLine +
        "      <vers:OrganisationCode>10391131</vers:OrganisationCode>" + newLine +
        "    </vers:DecSender>" + newLine +
        "    <vers:DecRecipient>" + newLine +
        "      <vers:OrganisationCode>10391131</vers:OrganisationCode>" + newLine +
        "    </vers:DecRecipient>" + newLine +
        "  </vers:Transport>" + newLine +
        "  <vers:RecordCreator>" + newLine +
        "    <vers:Organisation>" + newLine +
        "      <vers:Name>Nortal AS</vers:Name>" + newLine +
        "      <vers:OrganisationCode>10391131</vers:OrganisationCode>" + newLine +
        "    </vers:Organisation>" + newLine +
        "    <vers:Person>" + newLine +
        "      <vers:Name>Andres Traks</vers:Name>" + newLine +
        "    </vers:Person>" + newLine +
        "    <vers:ContactData>" + newLine +
        "      <vers:Phone>123</vers:Phone>" + newLine +
        "      <vers:Email>andres.traks@nortal.com</vers:Email>" + newLine +
        "    </vers:ContactData>" + newLine +
        "  </vers:RecordCreator>" + newLine +
        "  <vers:RecordSenderToDec>" + newLine +
        "    <vers:Organisation>" + newLine +
        "      <vers:Name>Nortal AS</vers:Name>" + newLine +
        "      <vers:OrganisationCode>10391131</vers:OrganisationCode>" + newLine +
        "      <vers:StructuralUnit>Kontor</vers:StructuralUnit>" + newLine +
        "      <vers:PositionTitle>Arendaja</vers:PositionTitle>" + newLine +
        "    </vers:Organisation>" + newLine +
        "    <vers:Person>" + newLine +
        "      <vers:Name>Andres Traks</vers:Name>" + newLine +
        "      <vers:PersonalIdCode>11111111111</vers:PersonalIdCode>" + newLine +
        "    </vers:Person>" + newLine +
        "    <vers:ContactData>" + newLine +
        "      <vers:Email>andres.traks@nortal.com</vers:Email>" + newLine +
        "      <vers:PostalAddress>" + newLine +
        "        <vers:Country>Eesti</vers:Country>" + newLine +
        "        <vers:County>Tartu maakond</vers:County>" + newLine +
        "        <vers:LocalGovernment>Tartu</vers:LocalGovernment>" + newLine +
        "        <vers:AdministrativeUnit>Kesklinn</vers:AdministrativeUnit>" + newLine +
        "        <vers:Street>Riia</vers:Street>" + newLine +
        "        <vers:HouseNumber>1</vers:HouseNumber>" + newLine +
        "      </vers:PostalAddress>" + newLine +
        "    </vers:ContactData>" + newLine +
        "  </vers:RecordSenderToDec>" + newLine +
        "  <vers:SignatureMetadata>" + newLine +
        "    <vers:SignatureType>Digitaalallkiri</vers:SignatureType>" + newLine +
        "    <vers:Signer>ANDRES TRAKS</vers:Signer>" + newLine +
        "    <vers:Verified>Allkiri on kehtiv</vers:Verified>" + newLine +
        //"    <vers:SignatureVerificationDate>2019-04-23T00:00:00.000+03:00</vers:SignatureVerificationDate>" + newLine +
        "  </vers:SignatureMetadata>" + newLine +
        "  <vers:File>" + newLine +
        "    <vers:FileGuid>b1b4732e-a710-4672-b0a0-38a4a56a4233</vers:FileGuid>" + newLine +
        "    <vers:FileName>test.txt</vers:FileName>" + newLine +
        "    <vers:MimeType>application/vnd.etsi.asic-e+zip</vers:MimeType>" + newLine +
        "    <vers:FileSize>10</vers:FileSize>" + newLine +
        "    <vers:ZipBase64Content>H4sIAAAAAAAAAABDQL</vers:ZipBase64Content>" + newLine +
        "  </vers:File>" + newLine +
        "</vers:DecContainer>";
    Assert.assertEquals(expected, xml);
  }


  private String serialize(XmlObject xmlObject, String methodName) throws IOException {
    XmlOptions options = new XmlOptions();
    options.setSaveNoXmlDecl();
    options.setCharacterEncoding("UTF-8");

    options.setSaveSyntheticDocumentElement(new QName("http://producers.dhl.xrd.riik.ee/producer/dhl",
            methodName,
            "ns5"));

    // fix DigiDoc client bug (also present with PostiPoiss doc-management-system)
    // they don't accept that SignedDoc have nameSpace alias set
    options.setSavePrettyPrint();
    HashMap<String, String> suggestedPrefixes = new HashMap<String, String>(2);
    suggestedPrefixes.put("http://www.sk.ee/DigiDoc/v1.3.0#", "");
    // suggestedPrefixes.put("http://www.sk.ee/DigiDoc/v1.4.0#", "");
    options.setSaveSuggestedPrefixes(suggestedPrefixes);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    xmlObject.save(outputStream, options);
    String xml = outputStream.toString("UTF-8");
    outputStream.close();
    return xml;
  }

  private String serializeItem(XmlObject xmlObject) throws IOException {
    XmlOptions options = new XmlOptions();
    options.setSaveNoXmlDecl();
    options.setCharacterEncoding("UTF-8");

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    xmlObject.save(outputStream, options);
    String xml = outputStream.toString("UTF-8");
    outputStream.close();
    return "<item>" + newLine +
        "  " + xml + newLine +
        "</item>";
  }

  private String serializeDecContainerDocument(XmlObject xmlObject) throws IOException {
    XmlOptions options = new XmlOptions();
    options.setSaveNoXmlDecl();
    options.setCharacterEncoding("UTF-8");

    options.setSavePrettyPrint();
    HashMap<String, String> suggestedPrefixes = new HashMap<String, String>(2);
    suggestedPrefixes.put("http://www.sk.ee/DigiDoc/v1.3.0#", "");
    // suggestedPrefixes.put("http://www.sk.ee/DigiDoc/v1.4.0#", "");
    options.setSaveSuggestedPrefixes(suggestedPrefixes);

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    xmlObject.save(outputStream, options);
    String xml = outputStream.toString("UTF-8");
    outputStream.close();
    return xml;
  }

  private Calendar getCalendar(String dateStr) throws ParseException {
    Calendar cal = Calendar.getInstance();
    cal.setTime(dateFormat.parse(dateStr));
    return cal;
  }
}
