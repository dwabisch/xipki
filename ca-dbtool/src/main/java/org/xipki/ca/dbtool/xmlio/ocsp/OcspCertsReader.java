/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.dbtool.xmlio.ocsp;

import java.io.InputStream;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.xipki.ca.dbtool.xmlio.DbDataObject;
import org.xipki.ca.dbtool.xmlio.DbiXmlReader;
import org.xipki.ca.dbtool.xmlio.InvalidDataObjectException;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

public class OcspCertsReader extends DbiXmlReader {

    public OcspCertsReader(InputStream xmlStream)
            throws XMLStreamException, InvalidDataObjectException {
        super(OcspCertType.TAG_PARENT, xmlStream);
    }

    @Override
    protected DbDataObject retrieveNext() throws InvalidDataObjectException, XMLStreamException {
        OcspCertType ret = null;
        StringBuilder buffer = new StringBuilder();
        int lastEvent = -1;

        while (reader.hasNext()) {
            int event = reader.next();
            String tagContent = null;

            if (event != XMLStreamConstants.CHARACTERS) {
                tagContent = buffer.toString();

                if (lastEvent == XMLStreamConstants.CHARACTERS) {
                    buffer.delete(0, buffer.length());
                }
            }

            lastEvent = event;

            switch (event) {
            case XMLStreamConstants.START_ELEMENT:
                if (OcspCertType.TAG_ROOT.equals(reader.getLocalName())) {
                    ret = new OcspCertType();
                }
                break;
            case XMLStreamConstants.CHARACTERS:
                buffer.append(reader.getText());
                break;
            case XMLStreamConstants.END_ELEMENT:
                if (ret == null) {
                    break;
                }

                switch (reader.getLocalName()) {
                case OcspCertType.TAG_ROOT:
                    ret.validate();
                    return ret;
                case OcspCertType.TAG_ID:
                    ret.setId(Long.parseLong(tagContent));
                    break;
                case OcspCertType.TAG_HASH:
                    ret.setHash(tagContent);
                    break;
                case OcspCertType.TAG_IID:
                    ret.setIid(Integer.parseInt(tagContent));
                    break;
                case OcspCertType.TAG_NAFTER:
                    ret.setNafter(Long.parseLong(tagContent));
                    break;
                case OcspCertType.TAG_NBEFORE:
                    ret.setNbefore(Long.parseLong(tagContent));
                    break;
                case OcspCertType.TAG_PROFILE:
                    ret.setProfile(tagContent);
                    break;
                case OcspCertType.TAG_REV:
                    ret.setRev(Boolean.parseBoolean(tagContent));
                    break;
                case OcspCertType.TAG_RIT:
                    ret.setRit(Long.parseLong(tagContent));
                    break;
                case OcspCertType.TAG_RR:
                    ret.setRr(Integer.parseInt(tagContent));
                    break;
                case OcspCertType.TAG_RT:
                    ret.setRt(Long.parseLong(tagContent));
                    break;
                case OcspCertType.TAG_SN:
                    ret.setSn(tagContent);
                    break;
                case OcspCertType.TAG_SUBJECT:
                    ret.setSubject(tagContent);
                    break;
                case OcspCertType.TAG_UPDATE:
                    ret.setUpdate(Long.parseLong(tagContent));
                    break;
                default:
                    break;
                } // end switch (reader.getLocalName())
                break;
            default:
                break;
            } // end switch (event)
        } // end while
        return null;
    } // method retrieveNext

}
