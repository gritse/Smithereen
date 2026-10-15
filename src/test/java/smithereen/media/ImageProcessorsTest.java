package smithereen.media;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.imageio.ImageIO;

import smithereen.libvips.VipsImage;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.media.MediaKind;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the image processors on generated files and a small animated GIF. Skipped when libvips can't be loaded.
 */
public class ImageProcessorsTest{
	private static boolean vipsAvailable;

	@TempDir
	Path tmp;
	// libvips caches loaded files by name, so every generated file gets a name of its own
	private int fileCounter;

	@BeforeAll
	static void checkVips(){
		try{
			new VipsImage("/nonexistent/file");
		}catch(IOException expected){
			vipsAvailable=true;
		}catch(Throwable x){
			vipsAvailable=false;
		}
	}

	private File animatedGif() throws IOException{
		Path f=tmp.resolve("anim.gif");
		try(InputStream in=getClass().getResourceAsStream("/media/anim.gif")){
			assertNotNull(in, "fixture missing");
			Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
		}
		return f.toFile();
	}

	private File stillImage(String format, int w, int h, boolean alpha) throws IOException{
		BufferedImage img=new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g=img.createGraphics();
		g.setColor(new Color(30, 120, 200));
		g.fillRect(0, 0, w/2, h);
		g.setColor(Color.RED);
		g.fillOval(w/4, h/4, w/2, h/2);
		g.dispose();
		File f=tmp.resolve("still"+(++fileCounter)+"."+format).toFile();
		assertTrue(ImageIO.write(img, format, f));
		return f;
	}

	@Test
	void sniffTellsStillFromAnimated() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		assertEquals(MediaKind.STILL_IMAGE, MediaSniffer.sniff(stillImage("png", 100, 80, false), "image/png"));
		assertEquals(MediaKind.ANIMATED_IMAGE, MediaSniffer.sniff(animatedGif(), "image/gif"));
		File text=tmp.resolve("text.txt").toFile();
		Files.writeString(text.toPath(), "not an image");
		MediaValidationException x=assertThrows(MediaValidationException.class, ()->MediaSniffer.sniff(text, "text/plain"));
		assertEquals(415, x.httpStatus);
	}

	@Test
	void stillImageIsScaledDownAndFlattened() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		ProcessedMedia out=new StillImageProcessor().process(stillImage("png", 3000, 1000, true), ProcessingOptions.DEFAULT);
		try{
			assertEquals(MediaFileType.IMAGE_PHOTO, out.type());
			assertEquals(2560, out.metadata().width());
			assertEquals(853, out.metadata().height());
			assertNotNull(out.metadata().blurhash());
			VipsImage check=new VipsImage(out.file().getAbsolutePath(), true);
			try{
				assertEquals(2560, check.getWidth());
				assertEquals(1, check.getLoadedFrames());
				assertFalse(check.hasAlpha(), "transparency should be flattened");
			}finally{
				check.release();
			}
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void smallStillImageKeepsItsSize() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		ProcessedMedia out=new StillImageProcessor().process(stillImage("jpg", 640, 480, false), ProcessingOptions.DEFAULT);
		try{
			assertEquals(640, out.metadata().width());
			assertEquals(480, out.metadata().height());
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void graffitiMustHaveTheExactSize() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		MediaValidationException x=assertThrows(MediaValidationException.class, ()->new StillImageProcessor().process(stillImage("png", 640, 480, false), ProcessingOptions.GRAFFITI));
		assertEquals(400, x.httpStatus);
		ProcessedMedia out=new StillImageProcessor().process(stillImage("png", ProcessingOptions.GRAFFITI.exactWidth(), ProcessingOptions.GRAFFITI.exactHeight(), false), ProcessingOptions.GRAFFITI);
		try{
			assertEquals(MediaFileType.IMAGE_GRAFFITI, out.type());
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void appLogoMustBeSquareAndBigEnough() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		MediaValidationException x=assertThrows(MediaValidationException.class, ()->new StillImageProcessor().process(stillImage("png", 400, 300, false), ProcessingOptions.APP_LOGO));
		assertEquals("err_app_logo_not_square", x.langKey);
		x=assertThrows(MediaValidationException.class, ()->new StillImageProcessor().process(stillImage("png", 100, 100, false), ProcessingOptions.APP_LOGO));
		assertEquals("err_app_logo_too_small", x.langKey);
		ProcessedMedia out=new StillImageProcessor().process(stillImage("png", 800, 800, false), ProcessingOptions.APP_LOGO);
		try{
			assertEquals(400, out.metadata().width());
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void animationKeepsItsFrames() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		ProcessedMedia out=new AnimatedImageProcessor().process(animatedGif(), ProcessingOptions.DEFAULT);
		try{
			assertEquals(MediaFileType.IMAGE_ANIMATED, out.type());
			assertEquals(300, out.metadata().width());
			assertEquals(200, out.metadata().height());
			assertNotNull(out.metadata().blurhash());
			VipsImage check=new VipsImage(out.file().getAbsolutePath(), true);
			try{
				assertEquals(12, check.getLoadedFrames());
				assertEquals(200, check.getFrameHeight());
			}finally{
				check.release();
			}
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void animationOverThePixelBudgetIsScaledDown() throws Exception{
		Assumptions.assumeTrue(vipsAvailable, "libvips not available");
		// 12 frames of 300x200 = 720,000 pixels; a budget of a quarter of that scales each side by half
		ProcessedMedia out=new AnimatedImageProcessor().process(animatedGif(), ProcessingOptions.DEFAULT.withMaxAnimationPixels(180_000));
		try{
			assertEquals(150, out.metadata().width());
			assertEquals(100, out.metadata().height());
			VipsImage check=new VipsImage(out.file().getAbsolutePath(), true);
			try{
				assertEquals(12, check.getLoadedFrames());
				assertEquals(100, check.getFrameHeight());
			}finally{
				check.release();
			}
		}finally{
			out.deleteFiles();
		}
	}

	@Test
	void purposesDecideWhichKindsAreAccepted(){
		assertTrue(MediaFileUploadPurpose.ATTACHMENT.accepts(MediaKind.ANIMATED_IMAGE));
		assertTrue(MediaFileUploadPurpose.ALBUM_PHOTO.accepts(MediaKind.ANIMATED_IMAGE));
		assertFalse(MediaFileUploadPurpose.AVATAR.accepts(MediaKind.ANIMATED_IMAGE));
		assertFalse(MediaFileUploadPurpose.APP_LOGO.accepts(MediaKind.ANIMATED_IMAGE));
		assertTrue(MediaFileUploadPurpose.AVATAR.accepts(MediaKind.STILL_IMAGE));
	}
}
