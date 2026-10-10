package smithereen.http;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpResponse;

public class ReaderBodyHandler implements HttpResponse.BodyHandler<Reader>{
	public static final long DEFAULT_SIZE_LIMIT=1024*1024;

	private final long sizeLimit;

	public ReaderBodyHandler(){
		this(DEFAULT_SIZE_LIMIT);
	}

	/**
	 * @param sizeLimit maximum response body size in bytes. Reading past it fails with an IOException.
	 */
	public ReaderBodyHandler(long sizeLimit){
		this.sizeLimit=sizeLimit;
	}

	@Override
	public HttpResponse.BodySubscriber<Reader> apply(HttpResponse.ResponseInfo responseInfo){
		return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(), in->new InputStreamReader(new LimitedInputStream(in, sizeLimit), HttpContentType.from(responseInfo.headers()).getCharset()));
	}
}
