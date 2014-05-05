package log_events.org.apache.hadoop;

import org.ngmon.logger.core.AbstractNamespace;


public class PortmapNamespace extends AbstractNamespace {

    public AbstractNamespace encountered(String eGetCause) {
        return this;
    }

    public AbstractNamespace failed_start_server_cause(String Exception) {
        return this;
    }

    public AbstractNamespace found_mapping_for_key_port(String key, int res) {
        return this;
    }

    public AbstractNamespace portmap_getport_key(String key, String mapping) {
        return this;
    }

    public AbstractNamespace portmap_remove_key(String key) {
        return this;
    }

    public AbstractNamespace portmap_server_started_tcp_udp(String tcpChannelGetLocalAddress, String udpChannelGetLocalAddress) {
        return this;
    }

    public AbstractNamespace portmap_set_key(String key) {
        return this;
    }

    public AbstractNamespace portmaphandler_unknown_rpc_procedure(int portmapProc) {
        return this;
    }

    public AbstractNamespace warning_mapping_for_key(String key) {
        return this;
    }

}
