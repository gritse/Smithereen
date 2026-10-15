package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import smithereen.libvips.VipsImage;
import smithereen.model.media.ImageMetadata;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaKind;
import smithereen.util.BlurHash;

/**
 * Animated GIFs and WebPs: all frames, flattened onto white, scaled down to the bound and the pixel budget, written as animated WebP.
 * The blurhash is that of the first frame.
 */
public final class AnimatedImageProcessor implements MediaProcessor{
	private static final Logger LOG=LoggerFactory.getLogger(AnimatedImageProcessor.class);

	@Override
	public MediaKind kind(){
		return MediaKind.ANIMATED_IMAGE;
	}

	@Override
	public ProcessedMedia process(File input, ProcessingOptions options) throws IOException, MediaValidationException{
		// The first frame alone is enough for validation and the blurhash; the full stack is read sequentially and can only be used once.
		VipsImage firstFrame=StillImageProcessor.load(input);
		VipsImage allFrames=null;
		File out=null;
		try{
			StillImageProcessor.validate(firstFrame, options);
			try{
				allFrames=new VipsImage(input.getAbsolutePath(), true);
			}catch(IOException x){
				LOG.warn("VipsImage error", x);
				throw MediaValidationException.unreadable();
			}
			allFrames=StillImageProcessor.flatten(allFrames);
			out=File.createTempFile("SmithereenUploadResized", ".webp");
			int[] outSize={0, 0};
			ImageEncoder.writeAnimation(allFrames, options.maxSize(), options.maxAnimationPixels(), out, outSize);
			ImageMetadata meta=new ImageMetadata(outSize[0], outSize[1], BlurHash.encode(firstFrame, 4, 4), null);
			ProcessedMedia result=new ProcessedMedia(out, MediaFileType.IMAGE_ANIMATED, meta);
			out=null;
			return result;
		}finally{
			firstFrame.release();
			if(allFrames!=null)
				allFrames.release();
			if(out!=null)
				out.delete();
		}
	}
}
