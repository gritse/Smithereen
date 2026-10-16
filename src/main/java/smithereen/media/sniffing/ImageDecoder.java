package smithereen.media.sniffing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import smithereen.libvips.VipsImage;
import smithereen.model.media.MediaKind;

/**
 * Recognizes anything libvips can read as a still or an animated image.
 */
public final class ImageDecoder extends MediaDecoder {
	private static final Logger LOG = LoggerFactory.getLogger(ImageDecoder.class);

	public ImageDecoder(MediaDecoder next) {
		super(next);
	}

	@Override
	protected MediaKind tryDecode(File file) {
		VipsImage img;

		try {
			img = new VipsImage(file.getAbsolutePath());
		} catch (IOException x) {
			LOG.debug("Not an image that libvips can read: {}", x.getMessage());
			return null;
		}

		try {
			return img.isAnimated() ? MediaKind.ANIMATED_IMAGE : MediaKind.STILL_IMAGE;
		} finally {
			img.release();
		}
	}
}
