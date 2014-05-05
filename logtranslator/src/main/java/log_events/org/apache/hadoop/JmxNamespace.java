package log_events.org.apache.hadoop;

import org.ngmon.logger.core.AbstractNamespace;


public class JmxNamespace extends AbstractNamespace {

    public AbstractNamespace caught_exception_while_processing_jmx_re(String Exception) {
        return this;
    }

    public AbstractNamespace getting_attribute_threw_exception(String prs, String oname, String Exception) {
        return this;
    }

    public AbstractNamespace listing_beans_for(String qry) {
        return this;
    }

    public AbstractNamespace problem_while_trying_process_jmx_query_w(String qry, String oname, String Exception) {
        return this;
    }

}
