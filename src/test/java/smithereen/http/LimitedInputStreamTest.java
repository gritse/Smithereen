package smithereen.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

public class LimitedInputStreamTest{
	@Test
	public void readsUpToLimit() throws IOException{
		try(InputStream in=new LimitedInputStream(new ByteArrayInputStream(new byte[100]), 100)){
			assertEquals(100, in.readAllBytes().length);
		}
	}

	@Test
	public void failsPastLimit(){
		assertThrows(IOException.class, ()->{
			try(InputStream in=new LimitedInputStream(new ByteArrayInputStream(new byte[101]), 100)){
				in.readAllBytes();
			}
		});
		assertThrows(IOException.class, ()->{
			try(InputStream in=new LimitedInputStream(new ByteArrayInputStream(new byte[101]), 100)){
				for(int i=0;i<101;i++){
					if(in.read()==-1)
						fail("Unexpected EOF");
				}
			}
		});
	}
}
