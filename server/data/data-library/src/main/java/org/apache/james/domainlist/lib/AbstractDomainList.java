/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.domainlist.lib;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.domainlist.api.DomainListException;
import org.apache.james.lifecycle.api.Configurable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.steveash.guavate.Guavate;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

/**
 * All implementations of the DomainList interface should extends this abstract
 * class
 */
public abstract class AbstractDomainList implements DomainList, Configurable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDomainList.class);

    protected static final String LOCALHOST = "localhost";
    public static final String CONFIGURE_AUTODETECT = "autodetect";
    public static final String CONFIGURE_AUTODETECT_IP = "autodetectIP";
    public static final String CONFIGURE_DEFAULT_DOMAIN = "defaultDomain";
    public static final String CONFIGURE_DOMAIN_NAMES = "domainnames.domainname";
    public static final String ENV_DOMAIN = "DOMAIN";

    private final DNSService dns;
    private final EnvDetector envDetector;
    private boolean autoDetect = true;
    private boolean autoDetectIP = true;
    private String defaultDomain;

    public AbstractDomainList(DNSService dns, EnvDetector envDetector) {
        this.dns = dns;
        this.envDetector = envDetector;
    }

    public AbstractDomainList(DNSService dns) {
        this(dns, new EnvDetector());
    }

    @Override
    public void configure(HierarchicalConfiguration config) throws ConfigurationException {
        setAutoDetect(config.getBoolean(CONFIGURE_AUTODETECT, true));
        setAutoDetectIP(config.getBoolean(CONFIGURE_AUTODETECT_IP, true));

        configureDefaultDomain(config);

        addEnvDomain();
        addConfiguredDomains(config);
    }

    protected void addConfiguredDomains(HierarchicalConfiguration config) {
        String[] configuredDomainNames = config.getStringArray(CONFIGURE_DOMAIN_NAMES);
        try {
            if (configuredDomainNames != null) {
                for (String domain : Arrays.asList(configuredDomainNames)) {
                    if (!containsDomainInternal(domain)) {
                        addDomain(domain.toLowerCase(Locale.US));
                    }
                }
            }
        } catch (DomainListException e) {
            throw Throwables.propagate(e);
        }
    }

    private void addEnvDomain() {
        String envDomain = envDetector.getEnv(ENV_DOMAIN);
        if (!Strings.isNullOrEmpty(envDomain)) {
            try {
                LOGGER.info("Adding environment defined domain {}", envDomain);
                addDomain(envDomain);
            } catch (DomainListException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    @VisibleForTesting void configureDefaultDomain(HierarchicalConfiguration config) throws ConfigurationException {
        try {
            setDefaultDomain(config.getString(CONFIGURE_DEFAULT_DOMAIN, LOCALHOST));

            String hostName = InetAddress.getLocalHost().getHostName();
            if (mayChangeDefaultDomain()) {
                setDefaultDomain(hostName);
            }
        } catch (UnknownHostException e) {
            LOGGER.warn("Unable to retrieve hostname.", e);
        } catch (DomainListException e) {
            LOGGER.error("An error occured while creating the default domain", e);
        }
    }

    private boolean mayChangeDefaultDomain() {
        return LOCALHOST.equals(defaultDomain);
    }

    private void setDefaultDomain(String defaultDomain) throws DomainListException {
        if (defaultDomain != null && !containsDomain(defaultDomain)) {
            addDomain(defaultDomain);
        }
        this.defaultDomain = defaultDomain;
    }

    @Override
    public String getDefaultDomain() throws DomainListException {
        if (defaultDomain!= null) {
            return defaultDomain;
        } else {
            throw new DomainListException("Null default domain. Domain list might not be configured yet.");
        }
    }

    @Override
    public boolean containsDomain(String domain) throws DomainListException {
        boolean internalAnswer = containsDomainInternal(domain);
        return internalAnswer || getDomains().contains(domain);
    }

    @Override
    public List<String> getDomains() throws DomainListException {
        List<String> domains = getDomainListInternal();

        // create mutable copy, some subclasses return ImmutableList
        ArrayList<String> mutableDomains = new ArrayList<>(domains);
        List<String> detectedDomains = detectDomains();
        mutableDomains.addAll(detectedDomains);
        mutableDomains.addAll(detectIps(mutableDomains));

        if (LOGGER.isDebugEnabled()) {
            for (String domain : mutableDomains) {
                LOGGER.debug("Handling mail for: " + domain);
            }
        }

        return ImmutableList.copyOf(mutableDomains);
    }

    private List<String> detectIps(ArrayList<String> mutableDomains) {
        if (autoDetectIP) {
            return getDomainsIP(mutableDomains, dns, LOGGER);
        }
        return ImmutableList.of();
    }

    private List<String> detectDomains() {
        if (autoDetect) {
            String hostName;
            try {
                hostName = getDNSServer().getHostName(getDNSServer().getLocalHost());
            } catch (UnknownHostException ue) {
                hostName = "localhost";
            }

            LOGGER.info("Local host is: " + hostName);
            if (hostName != null && !hostName.equals("localhost")) {
                return ImmutableList.of(hostName.toLowerCase(Locale.US));
            }
        }
        return ImmutableList.of();
    }

    /**
     * Return a List which holds all ipAddress of the domains in the given List
     * 
     * @param domains
     *            List of domains
     * @return domainIP List of ipaddress for domains
     */
    private static List<String> getDomainsIP(List<String> domains, DNSService dns, Logger log) {
        return domains.stream()
            .flatMap(domain -> getDomainIP(domain, dns, log).stream())
            .distinct()
            .collect(Guavate.toImmutableList());
    }

    /**
     * @see #getDomainsIP(List, DNSService, Logger)
     */
    private static List<String> getDomainIP(String domain, DNSService dns, Logger log) {
        try {
            return dns.getAllByName(domain).stream()
                .map(InetAddress::getHostAddress)
                .distinct()
                .collect(Guavate.toImmutableList());
        } catch (UnknownHostException e) {
            log.error("Cannot get IP address(es) for " + domain);
            return ImmutableList.of();
        }
    }

    /**
     * Set to true to autodetect the hostname of the host on which james is
     * running, and add this to the domain service Default is true
     * 
     * @param autoDetect
     *            set to <code>false</code> for disable
     */
    public synchronized void setAutoDetect(boolean autoDetect) {
        LOGGER.info("Set autodetect to: " + autoDetect);
        this.autoDetect = autoDetect;
    }

    /**
     * Set to true to lookup the ipaddresses for each given domain and add these
     * to the domain service Default is true
     * 
     * @param autoDetectIP
     *            set to <code>false</code> for disable
     */
    public synchronized void setAutoDetectIP(boolean autoDetectIP) {
        LOGGER.info("Set autodetectIP to: " + autoDetectIP);
        this.autoDetectIP = autoDetectIP;
    }

    /**
     * Return dnsServer
     * 
     * @return dns
     */
    protected DNSService getDNSServer() {
        return dns;
    }

    /**
     * Return domainList
     * 
     * @return List
     */
    protected abstract List<String> getDomainListInternal() throws DomainListException;

    protected abstract boolean containsDomainInternal(String domain) throws DomainListException;

}
