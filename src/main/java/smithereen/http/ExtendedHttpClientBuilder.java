package smithereen.http;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

public class ExtendedHttpClientBuilder implements HttpClient.Builder{
	private final HttpClient.Builder realBuilder;
	private boolean allowLocalNetworks=false;
	private HttpClient.Redirect redirectPolicy=HttpClient.Redirect.NEVER;

	public ExtendedHttpClientBuilder(HttpClient.Builder realBuilder){
		this.realBuilder=realBuilder;
	}

	@Override
	public ExtendedHttpClientBuilder cookieHandler(CookieHandler cookieHandler){
		realBuilder.cookieHandler(cookieHandler);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder connectTimeout(Duration duration){
		realBuilder.connectTimeout(duration);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder sslContext(SSLContext sslContext){
		realBuilder.sslContext(sslContext);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder sslParameters(SSLParameters sslParameters){
		realBuilder.sslParameters(sslParameters);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder executor(Executor executor){
		realBuilder.executor(executor);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder followRedirects(HttpClient.Redirect policy){
		redirectPolicy=policy;
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder version(HttpClient.Version version){
		realBuilder.version(version);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder priority(int priority){
		realBuilder.priority(priority);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder proxy(ProxySelector proxySelector){
		realBuilder.proxy(proxySelector);
		return this;
	}

	@Override
	public ExtendedHttpClientBuilder authenticator(Authenticator authenticator){
		realBuilder.authenticator(authenticator);
		return this;
	}

	public ExtendedHttpClientBuilder allowLocalNetworks(boolean allow){
		allowLocalNetworks=allow;
		return this;
	}

	@Override
	public HttpClient build(){
		// When local networks aren't allowed, redirects are followed by ExtendedHttpClient itself
		// so that every hop goes through the local address check
		realBuilder.followRedirects(allowLocalNetworks ? redirectPolicy : HttpClient.Redirect.NEVER);
		return new ExtendedHttpClient(realBuilder.build(), allowLocalNetworks, redirectPolicy);
	}
}
