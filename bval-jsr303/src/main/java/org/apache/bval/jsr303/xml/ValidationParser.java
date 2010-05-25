/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.bval.jsr303.xml;


import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import javax.validation.ConstraintValidatorFactory;
import javax.validation.MessageInterpolator;
import javax.validation.TraversableResolver;
import javax.validation.ValidationException;
import javax.validation.spi.ValidationProvider;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.bval.jsr303.ConfigurationImpl;
import org.apache.bval.jsr303.util.IOUtils;
import org.apache.bval.jsr303.util.SecureActions;
import org.apache.bval.util.PrivilegedActions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.SAXException;

/**
 * Description: uses jaxb to parse validation.xml<br/>
 */
public class ValidationParser {
    private static final String DEFAULT_VALIDATION_XML_FILE = "META-INF/validation.xml";
    private static final String VALIDATION_CONFIGURATION_XSD =
          "META-INF/validation-configuration-1.0.xsd";
    private static final Log log = LogFactory.getLog(ValidationParser.class);
    private final String validationXmlFile;

    public ValidationParser(String file) {
        if(file == null) {
            validationXmlFile = DEFAULT_VALIDATION_XML_FILE;
        } else {
            validationXmlFile = file;
        }
    }

    public void processValidationConfig(ConfigurationImpl targetConfig) {
        ValidationConfigType xmlConfig = parseXmlConfig();
        if (xmlConfig != null) {
            applyConfig(xmlConfig, targetConfig);
        }
    }

    private ValidationConfigType parseXmlConfig() {
        InputStream inputStream = null;
        try {
            inputStream = getInputStream(validationXmlFile);
            if (inputStream == null) {
                if (log.isDebugEnabled()) log.debug("No " + validationXmlFile +
                      " found. Using annotation based configuration only.");
                return null;
            }

            if (log.isDebugEnabled()) log.debug(validationXmlFile + " found.");

            Schema schema = getSchema();
            JAXBContext jc = JAXBContext.newInstance(ValidationConfigType.class);
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            unmarshaller.setSchema(schema);
            StreamSource stream = new StreamSource(inputStream);
            JAXBElement<ValidationConfigType> root =
                  unmarshaller.unmarshal(stream, ValidationConfigType.class);
            return root.getValue();
        } catch (JAXBException e) {
            throw new ValidationException("Unable to parse " + validationXmlFile, e);
        } catch (IOException e) {
            throw new ValidationException("Unable to parse " + validationXmlFile, e);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    private InputStream getInputStream(String path) throws IOException {
        ClassLoader loader = PrivilegedActions.getClassLoader(getClass());
        InputStream inputStream = loader.getResourceAsStream( path );
        
        if ( inputStream != null ) {
            // spec says: If more than one META-INF/validation.xml file
            // is found in the classpath, a ValidationException is raised.
            if ( path.equals("META-INF/validation.xml") ) {
                Enumeration<URL> urls = loader.getResources(path);
                if ( urls.hasMoreElements() && (urls.nextElement() != null) && urls.hasMoreElements() ) {
                    throw new ValidationException("More than one " + path + " is found in the classpath");
                }
            }
        }
        
        return inputStream;
    }

    private Schema getSchema() {
        return getSchema(VALIDATION_CONFIGURATION_XSD);
    }

    static Schema getSchema(String xsd) {
        ClassLoader loader = PrivilegedActions.getClassLoader(ValidationParser.class);
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        URL schemaUrl = loader.getResource(xsd);
        try {
            return sf.newSchema(schemaUrl);
        } catch (SAXException e) {
            log.warn("Unable to parse schema: " + xsd, e);
            return null;
        }
    }

    private void applyConfig(ValidationConfigType xmlConfig, ConfigurationImpl targetConfig) {
        applyProviderClass(xmlConfig, targetConfig);
        applyMessageInterpolator(xmlConfig, targetConfig);
        applyTraversableResolver(xmlConfig, targetConfig);
        applyConstraintFactory(xmlConfig, targetConfig);
        applyMappingStreams(xmlConfig, targetConfig);
        applyProperties(xmlConfig, targetConfig);
    }

    private void applyProperties(ValidationConfigType xmlConfig, ConfigurationImpl target) {
        for (PropertyType property : xmlConfig.getProperty()) {
            if (log.isDebugEnabled()) {
                log.debug("Found property '" + property.getName() + "' with value '" +
                      property.getValue() + "' in " + validationXmlFile);
            }
            target.addProperty(property.getName(), property.getValue());
        }
    }

    private void applyProviderClass(ValidationConfigType xmlConfig, ConfigurationImpl target) {
        String providerClassName = xmlConfig.getDefaultProvider();
        if (providerClassName != null) {
            Class<? extends ValidationProvider<?>> clazz =
                  (Class<? extends ValidationProvider<?>>) SecureActions
                        .loadClass(providerClassName, this.getClass());
            target.setProviderClass(clazz);
            if (log.isInfoEnabled())
                log.info("Using " + providerClassName + " as validation provider.");
        }
    }

    private void applyMessageInterpolator(ValidationConfigType xmlConfig,
                                          ConfigurationImpl target) {
        String messageInterpolatorClass = xmlConfig.getMessageInterpolator();
        if ( target.getMessageInterpolator() == null ) {
            if (messageInterpolatorClass != null) {
                Class<MessageInterpolator> clazz = (Class<MessageInterpolator>) SecureActions
                      .loadClass(messageInterpolatorClass, this.getClass());
                target.messageInterpolator(SecureActions.newInstance(clazz));
                if (log.isInfoEnabled())
                    log.info("Using " + messageInterpolatorClass + " as message interpolator.");
    
            }
        }
    }

    private void applyTraversableResolver(ValidationConfigType xmlConfig,
                                          ConfigurationImpl target) {
        String traversableResolverClass = xmlConfig.getTraversableResolver();
        if ( target.getTraversableResolver() == null ) {
            if (traversableResolverClass != null) {
                Class<TraversableResolver> clazz = (Class<TraversableResolver>) SecureActions
                      .loadClass(traversableResolverClass, this.getClass());
                target.traversableResolver(SecureActions.newInstance(clazz));
                if (log.isInfoEnabled())
                    log.info("Using " + traversableResolverClass + " as traversable resolver.");
            }
        }
    }

    private void applyConstraintFactory(ValidationConfigType xmlConfig,
                                        ConfigurationImpl target) {
        String constraintFactoryClass = xmlConfig.getConstraintValidatorFactory();
        if ( target.getConstraintValidatorFactory() == null ) {
            if (constraintFactoryClass != null) {
                Class<ConstraintValidatorFactory> clazz =
                      (Class<ConstraintValidatorFactory>) SecureActions
                            .loadClass(constraintFactoryClass, this.getClass());
                target.constraintValidatorFactory(SecureActions.newInstance(clazz));
                if (log.isInfoEnabled())
                    log.info("Using " + constraintFactoryClass + " as constraint factory.");
            }
        }
    }

    private void applyMappingStreams(ValidationConfigType xmlConfig,
                                     ConfigurationImpl target) {
        for (JAXBElement<String> mappingFileNameElement : xmlConfig.getConstraintMapping()) {
            String mappingFileName = mappingFileNameElement.getValue();
            if ( mappingFileName.startsWith("/") ) {
                // Classloader needs a path without a starting /
                mappingFileName = mappingFileName.substring(1);
            }
            if (log.isDebugEnabled()) {
                log.debug(
                      "Trying to open input stream for " + mappingFileName);
            }
            InputStream in = null;
            try {
                in = getInputStream(mappingFileName);
                if (in == null) {
                    throw new ValidationException(
                          "Unable to open input stream for mapping file " +
                                mappingFileName);
                }
            } catch (IOException e) {
                throw new ValidationException("Unable to open input stream for mapping file " +
                      mappingFileName, e);
            }
            target.addMapping(in);
        }
    }
}