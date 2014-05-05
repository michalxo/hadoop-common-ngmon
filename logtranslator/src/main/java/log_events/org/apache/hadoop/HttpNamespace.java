package log_events.org.apache.hadoop;

import org.ngmon.logger.core.AbstractNamespace;


public class HttpNamespace extends AbstractNamespace {

    public AbstractNamespace added_filter_class_context(String name, String classname, String webAppContextGetDisplayName) {
        return this;
    }

    public AbstractNamespace added_global_filter_class(String name, String classname) {
        return this;
    }

    public AbstractNamespace adding_kerberos_spnego_filter(String name) {
        return this;
    }

    public AbstractNamespace adding_path_spec(String path) {
        return this;
    }

    public AbstractNamespace addjerseyresourcepackage_packagename_pat(String packageName, String pathSpec) {
        return this;
    }

    public AbstractNamespace could_not_load_logjlogger_class(String Exception) {
        return this;
    }

    public AbstractNamespace error_while_stopping_listener_for_webapp(String webAppContextGetDisplayName, String Exception) {
        return this;
    }

    public AbstractNamespace error_while_stopping_web_app_context(String webAppContextGetDisplayName, String Exception) {
        return this;
    }

    public AbstractNamespace error_while_stopping_web_server_for(String webAppContextGetDisplayName, String Exception) {
        return this;
    }

    public AbstractNamespace http_request_log_for_could_not(String loggerName) {
        return this;
    }

    public AbstractNamespace http_request_log_for_not_defined(String loggerName) {
        return this;
    }

    public AbstractNamespace httpserver_start_threw_multiexception(String Exception) {
        return this;
    }

    public AbstractNamespace httpserver_start_threw_non_bind_ioexcept(String Exception) {
        return this;
    }

    public AbstractNamespace jetty_bound_port(String listenerGetLocalPort) {
        return this;
    }

    public AbstractNamespace jetty_request_log_can_only_enabled() {
        return this;
    }

    public AbstractNamespace jetty_request_log_for_was_wrong(String loggerName) {
        return this;
    }

    public AbstractNamespace should_not_used_instead_use(String DEPRECATED_UGI_KEY, String HADOOP_HTTP_STATIC_USER) {
        return this;
    }

}
