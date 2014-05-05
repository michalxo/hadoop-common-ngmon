package log_events.org.apache.hadoop;

import org.ngmon.logger.core.AbstractNamespace;


public class ServiceNamespace extends AbstractNamespace {

    public AbstractNamespace adding_service(String serviceGetName) {
        return this;
    }

    public AbstractNamespace config_has_been_overridden_during_init() {
        return this;
    }

    public AbstractNamespace exception_while_notifying_listeners(String abstractService, String Exception, String Exception2) {
        return this;
    }

    public AbstractNamespace ignoring_entrant_call_stop() {
        return this;
    }

    public AbstractNamespace initing_services_size(String getNameMethodCall, String servicesSize) {
        return this;
    }

    public AbstractNamespace notefailure(String exception, String str) {
        return this;
    }

    public AbstractNamespace service_entered_state(String getName, String getServiceState) {
        return this;
    }

    public AbstractNamespace service_failed_state_cause(String getName, String failureState, String exception, 
    					String exception2) {
        return this;
    }

    public AbstractNamespace service_started(String getName) {
        return this;
    }

    public AbstractNamespace starting_services_size(String getNameMethodCall, String servicesSize) {
        return this;
    }

    public AbstractNamespace stopping_service(int i, String service) {
        return this;
    }

    public AbstractNamespace stopping_services_size(String getNameMethodCall, int numOfServicesToStop) {
        return this;
    }

}
