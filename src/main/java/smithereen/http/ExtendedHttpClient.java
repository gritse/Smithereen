package smithereen.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import smithereen.BuildInfo;
import smithereen.Config;

public class ExtendedHttpClient extends HttpClient{
	private final HttpClient realClient;
	private static final Logger LOG=LoggerFactory.getLogger(ExtendedHttpClient.class);
	private final boolean allowLocalNetworks;
	private final Redirect redirectPolicy;
	private static final int MAX_REDIRECTS=5;
	private static final Set<String> CROSS_ORIGIN_STRIPPED_HEADERS=Set.of("authorization", "cookie", "signature", "signature-input");

	ExtendedHttpClient(HttpClient realClient, boolean allowLocalNetworks, Redirect redirectPolicy){
		this.realClient=realClient;
		this.allowLocalNetworks=allowLocalNetworks;
		this.redirectPolicy=redirectPolicy;
	}

	public static ExtendedHttpClientBuilder newBuilder(){
		return new ExtendedHttpClientBuilder(HttpClient.newBuilder());
	}

	@Override
	public Optional<CookieHandler> cookieHandler(){
		return realClient.cookieHandler();
	}

	@Override
	public Optional<Duration> connectTimeout(){
		return realClient.connectTimeout();
	}

	@Override
	public Redirect followRedirects(){
		return redirectPolicy;
	}

	@Override
	public Optional<ProxySelector> proxy(){
		return realClient.proxy();
	}

	@Override
	public SSLContext sslContext(){
		return realClient.sslContext();
	}

	@Override
	public SSLParameters sslParameters(){
		return realClient.sslParameters();
	}

	@Override
	public Optional<Authenticator> authenticator(){
		return realClient.authenticator();
	}

	@Override
	public Version version(){
		return realClient.version();
	}

	@Override
	public Optional<Executor> executor(){
		return realClient.executor();
	}

	@Override
	public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException{
		if(allowLocalNetworks)
			return realClient.send(maybeAddUserAgent(request), responseBodyHandler);
		checkNotLocalIP(request);
		if(redirectPolicy==Redirect.NEVER)
			return realClient.send(maybeAddUserAgent(request), responseBodyHandler);

		// Follow redirects manually so that each new destination is checked against local networks too
		HttpRequest currentRequest=request;
		for(int i=0;;i++){
			HttpResponse<T> resp=realClient.send(maybeAddUserAgent(currentRequest), info->isRedirect(info.statusCode()) && info.headers().firstValue("location").isPresent()
					? HttpResponse.BodySubscribers.<T>replacing(null) : responseBodyHandler.apply(info));
			int status=resp.statusCode();
			if(!isRedirect(status))
				return resp;
			String location=resp.headers().firstValue("location").orElse(null);
			if(location==null)
				return resp;
			if(i>=MAX_REDIRECTS)
				throw new IOException("Too many redirects");
			URI newURI;
			try{
				newURI=currentRequest.uri().resolve(location);
			}catch(IllegalArgumentException x){
				throw new IOException("Invalid redirect location: "+location, x);
			}
			String scheme=newURI.getScheme()==null ? "" : newURI.getScheme().toLowerCase(Locale.ROOT);
			if(!scheme.equals("http") && !scheme.equals("https"))
				throw new IOException("Invalid redirect location: "+location);
			if(redirectPolicy==Redirect.NORMAL && scheme.equals("http") && "https".equalsIgnoreCase(currentRequest.uri().getScheme()))
				throw new IOException("Refusing to follow a redirect from https to http");
			boolean sameOrigin=Objects.equals(currentRequest.uri().getScheme(), newURI.getScheme())
					&& Objects.equals(currentRequest.uri().getRawAuthority(), newURI.getRawAuthority());
			HttpRequest.Builder builder=HttpRequest.newBuilder(currentRequest, (k, v)->sameOrigin || !CROSS_ORIGIN_STRIPPED_HEADERS.contains(k.toLowerCase(Locale.ROOT))).uri(newURI);
			// Same method rewriting as the JDK client does
			String method=currentRequest.method();
			if(status==303 || ((status==301 || status==302) && method.equals("POST")))
				builder.GET();
			currentRequest=builder.build();
			checkNotLocalIP(currentRequest);
		}
	}

	private static boolean isRedirect(int status){
		return status==301 || status==302 || status==303 || status==307 || status==308;
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler){
		// TODO if these methods ever get used, might be a good idea to make this check async
		// Note: when local networks are not allowed, async requests don't follow redirects
		if(!allowLocalNetworks){
			try{
				checkNotLocalIP(request);
			}catch(IOException x){
				return CompletableFuture.failedFuture(x);
			}
		}
		return realClient.sendAsync(maybeAddUserAgent(request), responseBodyHandler);
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler){
		if(!allowLocalNetworks){
			try{
				checkNotLocalIP(request);
			}catch(IOException x){
				return CompletableFuture.failedFuture(x);
			}
		}
		return realClient.sendAsync(maybeAddUserAgent(request), responseBodyHandler, pushPromiseHandler);
	}

	@Override
	public WebSocket.Builder newWebSocketBuilder(){
		return realClient.newWebSocketBuilder();
	}

	@Override
	public void shutdown(){
		realClient.shutdown();
	}

	@Override
	public boolean awaitTermination(Duration duration) throws InterruptedException{
		return realClient.awaitTermination(duration);
	}

	@Override
	public boolean isTerminated(){
		return realClient.isTerminated();
	}

	@Override
	public void shutdownNow(){
		realClient.shutdownNow();
	}

	@Override
	public void close(){
		realClient.close();
	}

	private void checkNotLocalIP(HttpRequest req) throws IOException{
		try{
			InetAddress[] ips=InetAddress.getAllByName(req.uri().getHost());
			for(InetAddress ip:ips){
				if(isNonPublicAddress(ip)){
					LOG.trace("Failing request {} because the domain {} resolves to a local IP {}", req, req.uri().getHost(), ip);
					throw new IOException("Can't make a request to this address");
				}
			}
		}catch(UnknownHostException ignored){}
	}

	static boolean isNonPublicAddress(InetAddress ip){
		if(ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress() || ip.isMulticastAddress())
			return true;
		byte[] a=ip.getAddress();
		if(ip instanceof Inet4Address){
			int b0=a[0] & 0xFF, b1=a[1] & 0xFF;
			return b0==0 // 0.0.0.0/8 "this network"
					|| (b0==100 && (b1 & 0xC0)==64) // 100.64.0.0/10 carrier-grade NAT
					|| (b0==192 && b1==0 && (a[2] & 0xFF)==0) // 192.0.0.0/24 IETF protocol assignments
					|| (b0==198 && (b1 & 0xFE)==18) // 198.18.0.0/15 benchmarking
					|| b0>=240; // 240.0.0.0/4 reserved and broadcast
		}else if(ip instanceof Inet6Address){
			int b0=a[0] & 0xFF;
			if((b0 & 0xFE)==0xFC) // fc00::/7 unique local
				return true;
			if((b0 & 0xFF)==0xFE && (a[1] & 0xC0)==0xC0) // fec0::/10 deprecated site-local
				return true;
			// IPv4-compatible (::a.b.c.d), IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d) addresses
			boolean embedsIPv4;
			if(b0==0x00 && a[1]==0x64 && (a[2] & 0xFF)==0xff && (a[3] & 0xFF)==0x9b){
				embedsIPv4=true;
				for(int i=4;i<12;i++){
					if(a[i]!=0){
						embedsIPv4=false;
						break;
					}
				}
			}else{
				embedsIPv4=true;
				for(int i=0;i<10;i++){
					if(a[i]!=0){
						embedsIPv4=false;
						break;
					}
				}
				if(embedsIPv4 && !((a[10]==0 && a[11]==0) || ((a[10] & 0xFF)==0xFF && (a[11] & 0xFF)==0xFF)))
					embedsIPv4=false;
			}
			if(embedsIPv4){
				try{
					return isNonPublicAddress(InetAddress.getByAddress(new byte[]{a[12], a[13], a[14], a[15]}));
				}catch(UnknownHostException x){
					return true;
				}
			}
		}
		return false;
	}

	private HttpRequest maybeAddUserAgent(HttpRequest req){
		if(req.headers().firstValue("user-agent").isPresent())
			return req;
		return HttpRequest.newBuilder(req, (k, v)->true)
				.header("User-Agent", "Smithereen/"+BuildInfo.VERSION+" (+https://"+Config.domain+"/)")
				.build();
	}
}
