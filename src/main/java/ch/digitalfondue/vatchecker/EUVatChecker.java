/**
 * Copyright © 2018 digitalfondue (info@digitalfondue.ch)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.digitalfondue.vatchecker;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A small utility for calling the VIES webservice. See https://ec.europa.eu/taxation_customs/vies/ .
 *
 * The main entry points are {@link #doCheck(String, String)} and if more customization is needed {@link #doCheck(String, String, BiFunction)}.
 */
public class EUVatChecker {
    
    private static final Document BASE_DOCUMENT_TEMPLATE;

    private static final String ENDPOINT = "https://ec.europa.eu/taxation_customs/vies/services/checkVatService";
    private static final XPathExpression VALID_ELEMENT_MATCHER;
    private static final XPathExpression NAME_ELEMENT_MATCHER;
    private static final XPathExpression ADDRESS_ELEMENT_MATCHER;

    private final BiFunction<String, String, InputStream> documentFetcher;


    /**
     *
     */
    public EUVatChecker() {
        this(EUVatChecker::doCall);
    }

    /**
     * @param documentFetcher the function that, given the url of the web service and the body to post, return the resulting body as InputStream
     */
    public EUVatChecker(BiFunction<String, String, InputStream> documentFetcher) {
        this.documentFetcher = documentFetcher;
    }

    /**
     * See {@link #doCheck(String, String)}.
     *
     * @param countryCode 2 character ISO country code. Note: Greece is EL, not GR. See http://ec.europa.eu/taxation_customs/vies/faq.html#item_11
     * @param vatNr vat number
     * @return the response, see {@link EUVatCheckResponse}
     */
    public EUVatCheckResponse check(String countryCode, String vatNr) {
        return doCheck(countryCode, vatNr, this.documentFetcher);
    }

    static {
        XPath xPath = XPathFactory.newInstance().newXPath();
        try {
            VALID_ELEMENT_MATCHER = xPath.compile("//*[local-name()='checkVatResponse']/*[local-name()='valid']");
            NAME_ELEMENT_MATCHER = xPath.compile("//*[local-name()='checkVatResponse']/*[local-name()='name']");
            ADDRESS_ELEMENT_MATCHER = xPath.compile("//*[local-name()='checkVatResponse']/*[local-name()='address']");

            String soapCallTemplate = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                    "<soapenv:Header/>" +
                    "<soapenv:Body>" +
                    "<checkVat xmlns=\"urn:ec.europa.eu:taxud:vies:services:checkVat:types\">" +
                    "<countryCode></countryCode><vatNumber></vatNumber>" +
                    "</checkVat>" +
                    "</soapenv:Body>" +
                    "</soapenv:Envelope>";

            BASE_DOCUMENT_TEMPLATE = toDocument(new StringReader(soapCallTemplate));

        } catch (XPathExpressionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String prepareTemplate(String countryCode, String vatNumber) {
        Document doc = copyDocument(BASE_DOCUMENT_TEMPLATE);
        doc.getElementsByTagName("countryCode").item(0).setTextContent(countryCode);
        doc.getElementsByTagName("vatNumber").item(0).setTextContent(vatNumber);
        return fromDocument(doc);
    }

    private static Document copyDocument(Document document) {
        try {
            Transformer tx = getTransformer();
            DOMSource source = new DOMSource(document);
            DOMResult result = new DOMResult();
            tx.transform(source, result);
            return (Document) result.getNode();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Transformer getTransformer() throws TransformerConfigurationException {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException e) {
            try {
                tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (IllegalArgumentException e1) {}
        }
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException e) {}
        return tf.newTransformer();
    }

    private static Document toDocument(Reader reader) {
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setNamespaceAware(true);
            //
            setFeature(dbFactory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(dbFactory,"http://xml.org/sax/features/external-general-entities", false);
            setFeature(dbFactory,"http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(dbFactory,"http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbFactory.setXIncludeAware(false);
            dbFactory.setExpandEntityReferences(false);
            //
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            return dBuilder.parse(new InputSource(reader));
        } catch (ParserConfigurationException | IOException | SAXException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFeature(DocumentBuilderFactory dbFactory, String feature, boolean value) {
        try {
            dbFactory.setFeature(feature, value);
        } catch (ParserConfigurationException e) {
        }
    }

    private static String fromDocument(Document doc) {
        try {
            DOMSource domSource = new DOMSource(doc);
            Transformer transformer = getTransformer();
            StringWriter sw = new StringWriter();
            StreamResult sr = new StreamResult(sw);
            transformer.transform(domSource, sr);
            return sw.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }


    private static InputStream doCall(String endpointUrl, String document) {
        try {
            URL url = new URL(endpointUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/xml;charset=UTF-8");
            conn.setDoOutput(true);
            conn.getOutputStream().write(document.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();
            return conn.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Do a call to the EU vat checker web service.
     *
     * @param countryCode 2 character ISO country code. Note: Greece is EL, not GR. See http://ec.europa.eu/taxation_customs/vies/faq.html#item_11
     * @param vatNumber   the vat number to check
     * @return the response, see {@link EUVatCheckResponse}
     */
    public static EUVatCheckResponse doCheck(String countryCode, String vatNumber) {
        return doCheck(countryCode, vatNumber, EUVatChecker::doCall);
    }

    /**
     * See {@link #doCheck(String, String)}. This method accept a documentFetcher if you need to customize the
     * http client.
     *
     * @param countryCode     2 character ISO country code. Note: Greece is EL, not GR. See http://ec.europa.eu/taxation_customs/vies/faq.html#item_11
     * @param vatNumber       the vat number to check
     * @param documentFetcher the function that, given the url of the web service and the body to post, return the resulting body as InputStream
     * @return the response, see {@link EUVatCheckResponse}
     */
    public static EUVatCheckResponse doCheck(String countryCode, String vatNumber, BiFunction<String, String, InputStream> documentFetcher) {
        Objects.requireNonNull(countryCode, "countryCode cannot be null");
        Objects.requireNonNull(vatNumber, "vatNumber cannot be null");
        try {
            String body = prepareTemplate(countryCode, vatNumber);
            try (InputStream is = documentFetcher.apply(ENDPOINT, body); Reader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Document result = toDocument(isr);
                Node validNode = (Node) VALID_ELEMENT_MATCHER.evaluate(result, XPathConstants.NODE);
                if (validNode != null) {
                    Node nameNode = (Node) NAME_ELEMENT_MATCHER.evaluate(result, XPathConstants.NODE);
                    Node addressNode = (Node) ADDRESS_ELEMENT_MATCHER.evaluate(result, XPathConstants.NODE);
                    return new EUVatCheckResponse("true".equals(textNode(validNode)), textNode(nameNode), textNode(addressNode));
                } else {
                    return new EUVatCheckResponse(false, null, null);
                }
            }
        } catch (IOException | XPathExpressionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String textNode(Node node) {
        return node != null ? node.getTextContent() : null;
    }
}
