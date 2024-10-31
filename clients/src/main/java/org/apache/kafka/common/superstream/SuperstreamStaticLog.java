package org.apache.kafka.common.superstream;

import static org.apache.kafka.common.superstream.Consts.*;

public class SuperstreamStaticLog {
    static {
        if (Boolean.parseBoolean(System.getenv(SUPERSTREAM_DEBUG_ENV_VAR_ENV_VAR))) {
            System.out.println("Superstream library has been loaded.");
        }
    }
}
