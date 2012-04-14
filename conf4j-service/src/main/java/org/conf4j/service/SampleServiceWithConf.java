package org.conf4j.service;

import static org.conf4j.base.ConfElements.devoxx_agenda_url;
import static org.conf4j.base.ConfElements.devoxx_home_url;
import static org.conf4j.base.impl.ConfServiceImpl.CONF;

public class SampleServiceWithConf {
    public String getDevoxxHome() {
        return CONF.getValue(devoxx_home_url);
    }

    public String getDevoxxAgenda() {
        return CONF.getValue(devoxx_agenda_url);
    }
}