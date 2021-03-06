/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.conf4j.service;

import static org.conf4j.ConfElements.configuration_file;
import static org.conf4j.ConfElements.isConfigElement;
import static org.conf4j.service.ConfValue.ESource.CONFIG_FILE;
import static org.conf4j.service.ConfValue.ESource.CUSTOM;
import static org.conf4j.service.ConfValue.ESource.DEFAULT;
import static org.conf4j.service.ConfValue.ESource.JVM_PROPERTY;
import static org.conf4j.service.ConfValue.ESource.OS_PROPERTY;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.conf4j.Conf4j;
import org.conf4j.ConfElements;
import org.conf4j.ConfService;
import org.conf4j.service.ConfValue.ESource;
import org.conf4j.util.MacroEvaluator;
import org.conf4j.util.MacroParsingException;
import org.conf4j.util.MacroProcessor;

public enum ConfServiceInstance implements ConfService {
    CONF;

    private static final String LOGGER_CAT = "conf4j.console";
    private static final MessageFormat DIR_VAR_0_NOT_SET = new MessageFormat("directory variable ''{0}'' is not set.");
    private static final MessageFormat DIR_VAR_0_PATH_1_CREATED_ABSPATH_2 = new MessageFormat(
                    "directory variable ''{0}'' with path ''{1}'' doesn't exist, it is created successfully (absolute path is ''{2}'').");
    private static final MessageFormat DIR_VAR_0_PATH_1_CREATION_FAILED = new MessageFormat(
                    "directory variable ''{0}'' with path ''{1}'' doesn't exist, it is created, but creation failed.");
    private static final MessageFormat DIR_VAR_0_PATH_1_EXISTS = new MessageFormat(
                    "directory variable ''{0}'' with path ''{1}'' exists");
    private static final MessageFormat DIR_VAR_0_PATH_1_CHECKS_FAILURE = new MessageFormat(
                    "directory variable ''{0}'' with path ''{1}'' : failure when trying to check folder exists, or create folder.");
    private static final MessageFormat CATEGORY_2_VARIABLE_0_REF_UNKNOWN_VALUE_1 = new MessageFormat(
                    "Configuration variable ''{0}'' refers to unknown parameters that are not macro-expanded (expanded value is ''{1}'').\t//[{2}]");
    private static final MessageFormat CATEGORY_2_DESCRIPTION_3_EXPANDED_4_ACCESS_5_VARIABLE_0_VALUE_1 = new MessageFormat(
                    "## [{2}] {3}\n## expanded to ''{4}''\n## access count {5} \n{0}={1}\n");
    private static final MessageFormat VARIABLE_0_NOT_DECLARED_AS_CONFELEMENTS_MEMBER = new MessageFormat(
                    "Variable ''{0}'' is not declared as ConfElements#{0}");
    private static final MacroEvaluator EVALUATOR = new ConfValueEvaluator();
    private static final MacroEvaluator EVALUATOR_no_access_count = new ConfValueEvaluator(false);

    private transient ConfValueMap conf;
    private transient ConfServiceException initException;

    /**
     * @param key la valeur parse
     * @return Parse la valeur de ma propriété en considérant les séparateurs ',' et ";"; les valeurs parsées sont
     *         trimmées et les valeurs vides sont conservées.
     */
    @Override
    public final String[] getMultiValues(String key) {
        final String value = getValue(key);
        if (StringUtils.isEmpty(value)) {
            return new String[] {};
        }
        final String[] splitted = value.split("[,;]");
        for (int i = 0; i < splitted.length; i++) {
            splitted[i] = splitted[i].trim();
        }
        return splitted;
    }

    @Override
    public final boolean getBooleanValue(String key) {
        final String value = getValue(key);
        if (StringUtils.isEmpty(value)) {
            return false;
        }
        return "true".equalsIgnoreCase(value);
    }

    @Override
    public final int getIntegerValue(String key, int valueIfError) {
        final String value = getValue(key);
        if (StringUtils.isEmpty(value)) {
            return valueIfError;
        }
        try {
            return Integer.parseInt(getValue(key), 10);
        } catch (NumberFormatException e) {
            return valueIfError;
        }
    }

    @Override
    public final String getValue(String key) {
        return getValue(key, true);
    }

    private final String getValue(String key, boolean countAccess) {
        ensureInitialized();
        final ConfValue configValue = conf.get(key);
        if (configValue == null) {
            return null;
        }
        final String value = configValue.getValue(countAccess);
        try {
            return MacroProcessor.replaceProperties(value, conf, value, countAccess ? EVALUATOR
                            : EVALUATOR_no_access_count);
        } catch (MacroParsingException e) {
            throw new RuntimeException(value, e);
        }
    }

    @Override
    public final List<String> getKeys() {
        ensureInitialized();
        return new ArrayList<String>(new TreeSet<String>(conf.keySet()));
    }

    @Override
    public String setValue(String key, String value) {
        ensureInitialized();
        key = normalise(key, CUSTOM, value);
        final ConfValue configValue = conf.put(key, value, CUSTOM);
        return configValue != null ? configValue.getValue(false) : null;
    }

    @Override
    public void checkScope(PrintStream os) throws IOException {
        for (Map.Entry<String, ConfValue> entry : conf.entrySet()) {
            conf.checkScope(entry.getValue(), entry.getKey(), os);
        }
    }

    @Override
    public void checkUnused(PrintStream os) throws IOException {
        conf.checkUnused(os);
    }

    @Override
    public final synchronized void dumpConf(PrintStream os, boolean filter) {
        ensureInitialized();
        final List<String> keyList = new ArrayList<String>(conf.keySet());
        Collections.sort(keyList);

        for (String key : keyList) {
            if (filter && !isConfigElement(key)) {
                continue;
            }
            final ConfValue value = conf.get(key);
            os.println(CATEGORY_2_DESCRIPTION_3_EXPANDED_4_ACCESS_5_VARIABLE_0_VALUE_1.format(new Object[] { key,
                            value.getValue(false), value.getSource(), value.getDescription(), getValue(key, false),
                            value.getAccessCount() }));
            final String expandedValue = getValue(key, false);
            if (expandedValue != null && expandedValue.indexOf(MacroProcessor.REF_PREFIX) >= 0) {
                os.println(CATEGORY_2_VARIABLE_0_REF_UNKNOWN_VALUE_1.format(new Object[] { key, expandedValue,
                                value.getSource() }));
            }
        }
    }

    @Override
    public final void initFolders() {
        final Logger log = Logger.getLogger(LOGGER_CAT);
        final List<String> propertyNames = getKeys();
        for (int i = 0; i < propertyNames.size(); i++) {
            final String propertyName = propertyNames.get(i);
            if (!propertyName.endsWith("_dir")) {
                continue;
            }
            final String folderPath = getValue(propertyName);
            if (folderPath == null || folderPath.trim().length() <= 0) {
                log.info(DIR_VAR_0_NOT_SET.format(new String[] { propertyName }));
                continue;
            }
            final File folderFile = new File(folderPath);
            try {
                if (!folderFile.exists()) {
                    final boolean success = folderFile.mkdirs();
                    if (success) {
                        log.log(Level.INFO,
                                        DIR_VAR_0_PATH_1_CREATED_ABSPATH_2.format(new String[] { propertyName,
                                                        folderPath, folderFile.getAbsolutePath() }));
                    } else {
                        log.log(Level.SEVERE, DIR_VAR_0_PATH_1_CREATION_FAILED.format(new String[] { propertyName,
                                        folderPath }));
                    }
                } else {
                    log.log(Level.INFO, DIR_VAR_0_PATH_1_EXISTS.format(new String[] { propertyName, folderPath }));
                }
            } catch (SecurityException e) {
                log.log(Level.WARNING,
                                DIR_VAR_0_PATH_1_CHECKS_FAILURE.format(new String[] { propertyName, folderPath }));
            }
        }
    }

    private static final String normalise(final String initialName, ESource source, String value) {
        return initialName;
    }

    private final void ensureInitialized() {
        try {
            initConf();
        } catch (ConfServiceException e) {
            throw new RuntimeException(e);
        }
    }

    private final synchronized void initConf() throws ConfServiceException {
        if (conf != null) {
            if (initException != null)
                throw initException;
            return;
        }
        try {
            conf = new ConfValueMap();
            initDefault(conf);
            initOS(conf);
            initJVM(conf);
            initPublicFile(conf);
        } catch (Throwable t) {
            initException = t instanceof ConfServiceException ? (ConfServiceException) t : new ConfServiceException(t);
            throw initException;
        }
    }

    private static final void initDefault(ConfValueMap conf) throws ConfServiceException {
        for (Field field : ConfElements.class.getDeclaredFields()) {
            final Conf4j annotation = field.getAnnotation(Conf4j.class);
            if (annotation == null)
                continue;
            conf.put(field.getName(),
                            new ConfValue(annotation.value(), DEFAULT, annotation.description(), Arrays
                                            .asList(annotation.scope()), annotation.devPurposeOnly()));
        }
    }

    private static final void initOS(ConfValueMap conf) {
        final Properties properties = Environment.getProperties();
        final Enumeration<?> e = properties.keys();
        while (e.hasMoreElements()) {
            String name = (String) e.nextElement();
            final String value = properties.getProperty(name);
            name = normalise(name, OS_PROPERTY, value);
            conf.put(name, value, OS_PROPERTY);
        }
    }

    private static final void initJVM(ConfValueMap conf) {
        final Properties properties = System.getProperties();
        final Enumeration<?> e = properties.keys();
        while (e.hasMoreElements()) {
            String name = (String) e.nextElement();
            final String value = properties.getProperty(name);
            name = normalise(name, JVM_PROPERTY, value);
            conf.put(name, value, JVM_PROPERTY);
        }
    }

    /**
     * SYSTEM and JVM conf must be already initialized
     * 
     * WARNING: do NOT macro-expand ''configuration_file''. Indeed, this parameter A points itself to a file that
     * contains parameters B,C,D... that could be part of the parameter A definition. This being inconsistent, the
     * solution chosen is NOT to macro-expand 'configuration_file' value.
     */
    private static final void initPublicFile(ConfValueMap conf) throws ConfServiceException {
        final ConfValue filePath = conf.get(configuration_file);
        initFile(CONFIG_FILE, conf, configuration_file, filePath);
    }

    private static final void initFile(ESource source, ConfValueMap conf, String settingKey, ConfValue filePath)
                    throws ConfServiceException {
        if (filePath == null)
            return;
        final Properties properties = new Properties();
        try {
            final ConfValueEvaluator evaluator = new ConfValueEvaluator(false);
            final InputStream is = new FileInputStream(MacroProcessor.replaceProperties(filePath.getValue(false), conf,
                            settingKey, evaluator));
            try {
                properties.load(is);
            } finally {
                is.close();
            }
        } catch (IOException e) {
            // do nothing
        } catch (MacroParsingException e) {
            throw new ConfServiceException(e.getMessage(), e);
        }
        final Enumeration<?> e = properties.keys();
        while (e.hasMoreElements()) {
            String name = (String) e.nextElement();
            final String value = properties.getProperty(name);
            name = normalise(name, source, value);
            if (!isConfigElement(name))
                throw new ConfServiceException(
                                VARIABLE_0_NOT_DECLARED_AS_CONFELEMENTS_MEMBER.format(new String[] { name }));
            conf.put(name, value, source);
        }
    }

}
