package smithereen.http;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

public class LocalAddressCheckTest{
	private static boolean isNonPublic(String ip) throws UnknownHostException{
		return ExtendedHttpClient.isNonPublicAddress(InetAddress.getByName(ip));
	}

	@Test
	public void blocksNonPublicIPv4() throws UnknownHostException{
		for(String ip:new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1",
				"100.127.255.255", "192.0.0.1", "198.18.0.1", "224.0.0.1", "240.0.0.1", "255.255.255.255"}){
			assertTrue(isNonPublic(ip), ip);
		}
	}

	@Test
	public void blocksNonPublicIPv6() throws UnknownHostException{
		for(String ip:new String[]{"::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "fec0::1", "ff02::1",
				"::ffff:127.0.0.1", "::ffff:169.254.169.254", "::10.0.0.1", "64:ff9b::a9fe:a9fe"}){
			assertTrue(isNonPublic(ip), ip);
		}
	}

	@Test
	public void allowsPublicAddresses() throws UnknownHostException{
		for(String ip:new String[]{"1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "198.20.0.1", "2606:4700::1111",
				"::ffff:8.8.8.8", "64:ff9b::808:808"}){
			assertFalse(isNonPublic(ip), ip);
		}
	}
}
