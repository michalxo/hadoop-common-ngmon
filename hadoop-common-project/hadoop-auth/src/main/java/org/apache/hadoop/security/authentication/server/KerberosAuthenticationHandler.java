/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package org.apache.hadoop.security.authentication.server;

import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.security.authentication.client.KerberosAuthenticator;
import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.security.authentication.util.KerberosName;
import org.apache.hadoop.security.authentication.util.KerberosUtil;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;
import log_events.org.apache.hadoop.SecurityNamespace;
import org.ngmon.logger.core.SimpleLogger;
import org.ngmon.logger.logtranslator.LogGlobal;
import org.ngmon.logger.core.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.apache.hadoop.util.PlatformName.IBM_JAVA;

/**
 * The {@link KerberosAuthenticationHandler} implements the Kerberos SPNEGO authentication mechanism for HTTP.
 * <p/>
 * The supported configuration properties are:
 * <ul>
 * <li>kerberos.principal: the Kerberos principal to used by the server. As stated by the Kerberos SPNEGO
 * specification, it should be <code>HTTP/${HOSTNAME}@{REALM}</code>. The realm can be omitted from the
 * principal as the JDK GSS libraries will use the realm name of the configured default realm.
 * It does not have a default value.</li>
 * <li>kerberos.keytab: the keytab file containing the credentials for the Kerberos principal.
 * It does not have a default value.</li>
 * <li>kerberos.name.rules: kerberos names rules to resolve principal names, see 
 * {@link KerberosName#setRules(String)}</li>
 * </ul>
 */
public class KerberosAuthenticationHandler implements AuthenticationHandler {
  private static /* LoggerLOG=LoggerFactory.getLogger(KerberosAuthenticationHandler.class); */
			SecurityNamespace LOG = LoggerFactory.getLogger(SecurityNamespace.class, new SimpleLogger());

  /**
   * Kerberos context configuration for the JDK GSS library.
   */
  private static class KerberosConfiguration extends Configuration {
    private String keytab;
    private String principal;

    public KerberosConfiguration(String keytab, String principal) {
      this.keytab = keytab;
      this.principal = principal;
    }

    @Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
      Map<String, String> options = new HashMap<String, String>();
      if (IBM_JAVA) {
        options.put("useKeytab",
            keytab.startsWith("file://") ? keytab : "file://" + keytab);
        options.put("principal", principal);
        options.put("credsType", "acceptor");
      } else {
        options.put("keyTab", keytab);
        options.put("principal", principal);
        options.put("useKeyTab", "true");
        options.put("storeKey", "true");
        options.put("doNotPrompt", "true");
        options.put("useTicketCache", "true");
        options.put("renewTGT", "true");
        options.put("isInitiator", "false");
      }
      options.put("refreshKrb5Config", "true");
      String ticketCache = System.getenv("KRB5CCNAME");
      if (ticketCache != null) {
        if (IBM_JAVA) {
          options.put("useDefaultCcache", "true");
          // The first value searched when "useDefaultCcache" is used.
          System.setProperty("KRB5CCNAME", ticketCache);
          options.put("renewTGT", "true");
          options.put("credsType", "both");
        } else {
          options.put("ticketCache", ticketCache);
        }
      }
      if (LogGlobal.isDebugEnabled()) {
        options.put("debug", "true");
      }

      return new AppConfigurationEntry[]{
          new AppConfigurationEntry(KerberosUtil.getKrb5LoginModuleName(),
                                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                  options),};
    }
  }

  /**
   * Constant that identifies the authentication mechanism.
   */
  public static final String TYPE = "kerberos";

  /**
   * Constant for the configuration property that indicates the kerberos principal.
   */
  public static final String PRINCIPAL = TYPE + ".principal";

  /**
   * Constant for the configuration property that indicates the keytab file path.
   */
  public static final String KEYTAB = TYPE + ".keytab";

  /**
   * Constant for the configuration property that indicates the Kerberos name
   * rules for the Kerberos principals.
   */
  public static final String NAME_RULES = TYPE + ".name.rules";

  private String principal;
  private String keytab;
  private GSSManager gssManager;
  private LoginContext loginContext;

  /**
   * Initializes the authentication handler instance.
   * <p/>
   * It creates a Kerberos context using the principal and keytab specified in the configuration.
   * <p/>
   * This method is invoked by the {@link AuthenticationFilter#init} method.
   *
   * @param config configuration properties to initialize the handler.
   *
   * @throws ServletException thrown if the handler could not be initialized.
   */
  @Override
  public void init(Properties config) throws ServletException {
    try {
      principal = config.getProperty(PRINCIPAL, principal);
      if (principal == null || principal.trim().length() == 0) {
        throw new ServletException("Principal not defined in configuration");
      }
      keytab = config.getProperty(KEYTAB, keytab);
      if (keytab == null || keytab.trim().length() == 0) {
        throw new ServletException("Keytab not defined in configuration");
      }
      if (!new File(keytab).exists()) {
        throw new ServletException("Keytab does not exist: " + keytab);
      }

      String nameRules = config.getProperty(NAME_RULES, null);
      if (nameRules != null) {
        KerberosName.setRules(nameRules);
      }
      
      Set<Principal> principals = new HashSet<Principal>();
      principals.add(new KerberosPrincipal(principal));
      Subject subject = new Subject(false, principals, new HashSet<Object>(), new HashSet<Object>());

      KerberosConfiguration kerberosConfiguration = new KerberosConfiguration(keytab, principal);

      /* LOG.info("Login using keytab "+keytab+", for principal "+principal) */
      LOG.login_using_keytab_for_principal(keytab, principal).info();
      loginContext = new LoginContext("", subject, null, kerberosConfiguration);
      loginContext.login();

      Subject serverSubject = loginContext.getSubject();
      try {
        gssManager = Subject.doAs(serverSubject, new PrivilegedExceptionAction<GSSManager>() {

          @Override
          public GSSManager run() throws Exception {
            return GSSManager.getInstance();
          }
        });
      } catch (PrivilegedActionException ex) {
        throw ex.getException();
      }
      /* LOG.info("Initialized, principal [{}] from keytab [{}]",principal,keytab) */
      LOG.initialized_principal_from_keytab(principal, keytab).info();
    } catch (Exception ex) {
      throw new ServletException(ex);
    }
  }

  /**
   * Releases any resources initialized by the authentication handler.
   * <p/>
   * It destroys the Kerberos context.
   */
  @Override
  public void destroy() {
    try {
      if (loginContext != null) {
        loginContext.logout();
        loginContext = null;
      }
    } catch (LoginException ex) {
      /* LOG.warn(ex.getMessage(),ex) */
      LOG.exgetmessageexce(String.valueOf(ex.getMessage()), ex.toString()).tag("methodCall").warn();
    }
  }

  /**
   * Returns the authentication type of the authentication handler, 'kerberos'.
   * <p/>
   *
   * @return the authentication type of the authentication handler, 'kerberos'.
   */
  @Override
  public String getType() {
    return TYPE;
  }

  /**
   * Returns the Kerberos principal used by the authentication handler.
   *
   * @return the Kerberos principal used by the authentication handler.
   */
  protected String getPrincipal() {
    return principal;
  }

  /**
   * Returns the keytab used by the authentication handler.
   *
   * @return the keytab used by the authentication handler.
   */
  protected String getKeytab() {
    return keytab;
  }

  /**
   * This is an empty implementation, it always returns <code>TRUE</code>.
   *
   *
   *
   * @param token the authentication token if any, otherwise <code>NULL</code>.
   * @param request the HTTP client request.
   * @param response the HTTP client response.
   *
   * @return <code>TRUE</code>
   * @throws IOException it is never thrown.
   * @throws AuthenticationException it is never thrown.
   */
  @Override
  public boolean managementOperation(AuthenticationToken token,
                                     HttpServletRequest request,
                                     HttpServletResponse response)
    throws IOException, AuthenticationException {
    return true;
  }

  /**
   * It enforces the the Kerberos SPNEGO authentication sequence returning an {@link AuthenticationToken} only
   * after the Kerberos SPNEGO sequence has completed successfully.
   * <p/>
   *
   * @param request the HTTP client request.
   * @param response the HTTP client response.
   *
   * @return an authentication token if the Kerberos SPNEGO sequence is complete and valid,
   *         <code>null</code> if it is in progress (in this case the handler handles the response to the client).
   *
   * @throws IOException thrown if an IO error occurred.
   * @throws AuthenticationException thrown if Kerberos SPNEGO sequence failed.
   */
  @Override
  public AuthenticationToken authenticate(HttpServletRequest request, final HttpServletResponse response)
    throws IOException, AuthenticationException {
    AuthenticationToken token = null;
    String authorization = request.getHeader(KerberosAuthenticator.AUTHORIZATION);

    if (authorization == null || !authorization.startsWith(KerberosAuthenticator.NEGOTIATE)) {
      response.setHeader(KerberosAuthenticator.WWW_AUTHENTICATE, KerberosAuthenticator.NEGOTIATE);
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      if (authorization == null) {
        /* LOG.trace("SPNEGO starting") */
        LOG.spnego_starting().trace();
      } else {
        /* LOG.warn("'"+KerberosAuthenticator.AUTHORIZATION+"' does not start with '"+KerberosAuthenticator.NEGOTIATE+"' :  {}",authorization) */
        LOG.does_not_start_with(String.valueOf(KerberosAuthenticator.AUTHORIZATION), String.valueOf(KerberosAuthenticator.NEGOTIATE), authorization).tag("methodCall").warn();
      }
    } else {
      authorization = authorization.substring(KerberosAuthenticator.NEGOTIATE.length()).trim();
      final Base64 base64 = new Base64(0);
      final byte[] clientToken = base64.decode(authorization);
      Subject serverSubject = loginContext.getSubject();
      try {
        token = Subject.doAs(serverSubject, new PrivilegedExceptionAction<AuthenticationToken>() {

          @Override
          public AuthenticationToken run() throws Exception {
            AuthenticationToken token = null;
            GSSContext gssContext = null;
            GSSCredential gssCreds = null;
            try {
              if (IBM_JAVA) {
                // IBM JDK needs non-null credentials to be passed to createContext here, with
                // SPNEGO mechanism specified, otherwise JGSS will use its default mechanism
                // only, which is Kerberos V5.
                gssCreds = gssManager.createCredential(null, GSSCredential.INDEFINITE_LIFETIME,
                    new Oid[]{KerberosUtil.getOidInstance("GSS_SPNEGO_MECH_OID"),
                        KerberosUtil.getOidInstance("GSS_KRB5_MECH_OID")},
                    GSSCredential.ACCEPT_ONLY);
              }
              gssContext = gssManager.createContext(gssCreds);
              byte[] serverToken = gssContext.acceptSecContext(clientToken, 0, clientToken.length);
              if (serverToken != null && serverToken.length > 0) {
                String authenticate = base64.encodeToString(serverToken);
                response.setHeader(KerberosAuthenticator.WWW_AUTHENTICATE,
                                   KerberosAuthenticator.NEGOTIATE + " " + authenticate);
              }
              if (!gssContext.isEstablished()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                /* LOG.trace("SPNEGO in progress") */
                LOG.spnego_progress().trace();
              } else {
                String clientPrincipal = gssContext.getSrcName().toString();
                KerberosName kerberosName = new KerberosName(clientPrincipal);
                String userName = kerberosName.getShortName();
                token = new AuthenticationToken(userName, clientPrincipal, getType());
                response.setStatus(HttpServletResponse.SC_OK);
                /* LOG.trace("SPNEGO completed for principal [{}]",clientPrincipal) */
                LOG.spnego_completed_for_principal(clientPrincipal).trace();
              }
            } finally {
              if (gssContext != null) {
                gssContext.dispose();
              }
              if (gssCreds != null) {
                gssCreds.dispose();
              }
            }
            return token;
          }
        });
      } catch (PrivilegedActionException ex) {
        if (ex.getException() instanceof IOException) {
          throw (IOException) ex.getException();
        }
        else {
          throw new AuthenticationException(ex.getException());
        }
      }
    }
    return token;
  }

}
