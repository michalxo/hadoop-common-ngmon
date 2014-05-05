package org.ngmon.logger.core;

import org.apache.logging.log4j.LogManager;
import org.ngmon.logger.core.Logger;
import org.ngmon.logger.util.JSONer;

import java.util.Arrays;
import java.util.List;

public class SimpleLogger implements Logger {

//    private org.apache.logging.log4j.Logger log = LogManager.getLogger("Log4jLogger");

    private org.apache.logging.log4j.Logger log = LogManager.getLogger("Log4jLogger");


    public void log(String fqnNS, String methodName, List<String> tags, String[] paramNames, Object[] paramValues, int level) {
//        System.out.println(JSONer.getEventJson(fqnNS, methodName, tags, paramNames, paramValues, level));
        log.debug(JSONer.getEventJson(fqnNS, methodName, tags, paramNames, paramValues, level));
    }
}
