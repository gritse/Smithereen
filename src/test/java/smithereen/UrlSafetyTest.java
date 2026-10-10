package smithereen;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UrlSafetyTest{
	@BeforeAll
	public static void setup(){
		Config.domain="smithereen.local";
	}

	@Test
	public void websiteURLAllowsOnlyHttp(){
		assertEquals("https://example.com/", Utils.sanitizeWebsiteURL("https://example.com/"));
		assertEquals("http://example.com", Utils.sanitizeWebsiteURL("http://example.com"));
		assertEquals("http://example.com", Utils.sanitizeWebsiteURL("example.com"));
		assertNull(Utils.sanitizeWebsiteURL("javascript:alert(1)"));
		assertNull(Utils.sanitizeWebsiteURL("JavaScript:alert(1)"));
		assertNull(Utils.sanitizeWebsiteURL("data:text/html,<script>alert(1)</script>"));
		assertNull(Utils.sanitizeWebsiteURL("vbscript:msgbox"));
		assertNull(Utils.sanitizeWebsiteURL(""));
		assertNull(Utils.sanitizeWebsiteURL(null));
		assertNull(Utils.sanitizeWebsiteURL("https://exa\nmple.com/"));
	}

	@Test
	public void redirectTargetsMustBeLocal(){
		assertTrue(Utils.isSafeLocalRedirect("/feed"));
		assertTrue(Utils.isSafeLocalRedirect("/users/1?x=1"));
		assertTrue(Utils.isSafeLocalRedirect("https://smithereen.local/feed"));
		assertFalse(Utils.isSafeLocalRedirect("//evil.example/"));
		assertFalse(Utils.isSafeLocalRedirect("/\\evil.example/"));
		assertFalse(Utils.isSafeLocalRedirect("https://evil.example/"));
		assertFalse(Utils.isSafeLocalRedirect("javascript:alert(1)"));
		assertFalse(Utils.isSafeLocalRedirect(""));
		assertFalse(Utils.isSafeLocalRedirect(null));
	}
}
