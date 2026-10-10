package smithereen.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that fails with an IOException once more than a given number of bytes have been read.
 * Used to bound the size of responses received from remote servers so a malicious server can't exhaust memory.
 */
public class LimitedInputStream extends FilterInputStream{
	private final long limit;
	private long read;

	public LimitedInputStream(InputStream in, long limit){
		super(in);
		this.limit=limit;
	}

	private void checkLimit(long newBytes) throws IOException{
		read+=newBytes;
		if(read>limit)
			throw new IOException("Response body exceeds the limit of "+limit+" bytes");
	}

	@Override
	public int read() throws IOException{
		int b=super.read();
		if(b!=-1)
			checkLimit(1);
		return b;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		int n=super.read(b, off, len);
		if(n>0)
			checkLimit(n);
		return n;
	}

	@Override
	public long skip(long n) throws IOException{
		long skipped=super.skip(n);
		if(skipped>0)
			checkLimit(skipped);
		return skipped;
	}

	@Override
	public boolean markSupported(){
		return false;
	}
}
