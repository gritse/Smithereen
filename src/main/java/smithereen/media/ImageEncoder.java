package smithereen.media;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

import smithereen.libvips.VipsImage;

/**
 * Writes libvips images as WebP, scaling them down to fit a bound. Shared by the image processors.
 */
final class ImageEncoder{
	static final int QUALITY_PHOTO=93;
	/**
	 * libvips metadata fields that describe an animation. They must survive until the image is written, otherwise it becomes a still.
	 */
	private static final Set<String> ANIMATION_METADATA_FIELDS=Set.of("page-height", "n-pages", "delay", "loop");

	private ImageEncoder(){}

	/**
	 * Writes a still image, scaled down so that its longest side is at most {@code maxSize}.
	 * @param outSize receives the width and height of the result
	 */
	static void writeStill(VipsImage img, int maxSize, boolean lossless, File file, int[] outSize) throws IOException{
		double factor=(double) maxSize/(double) Math.max(img.getWidth(), img.getHeight());
		ArrayList<String> args=saveArgs(lossless);
		if(img.hasColorProfile()){
			keepOnlyFields(img, false);
		}else{
			args.add("strip=true");
		}
		if(factor>=1.0){
			img.writeToFile(file.getAbsolutePath()+"["+String.join(",", args)+"]");
			outSize[0]=img.getWidth();
			outSize[1]=img.getHeight();
		}else{
			VipsImage resized=img.resize(factor);
			try{
				resized.writeToFile(file.getAbsolutePath()+"["+String.join(",", args)+"]");
				outSize[0]=resized.getWidth();
				outSize[1]=resized.getHeight();
			}finally{
				resized.release();
			}
		}
	}

	/**
	 * Writes an animation, loaded with all its frames stacked vertically, as an animated WebP. Frames are scaled down so that
	 * the longest side is at most {@code maxSize} and the total number of pixels across all frames is at most {@code maxTotalPixels}.
	 * @param outSize receives the width and height of one frame of the result
	 */
	static void writeAnimation(VipsImage img, int maxSize, long maxTotalPixels, File file, int[] outSize) throws IOException{
		int frameHeight=img.getFrameHeight();
		int frameCount=img.getLoadedFrames();
		double factor=Math.min(1.0, (double) maxSize/(double) Math.max(img.getWidth(), frameHeight));
		double totalPixels=(double) img.getWidth()*frameHeight*frameCount*factor*factor;
		if(totalPixels>maxTotalPixels)
			factor*=Math.sqrt(maxTotalPixels/totalPixels);

		ArrayList<String> args=saveArgs(false);
		keepOnlyFields(img, true);

		if(factor>=1.0){
			img.writeToFile(file.getAbsolutePath()+"["+String.join(",", args)+"]");
			outSize[0]=img.getWidth();
			outSize[1]=frameHeight;
			return;
		}
		// Resize the whole stack of frames at once, choosing the scales so that every frame ends up with the same integer height
		int resizedFrameHeight=Math.max(1, (int) Math.round(frameHeight*factor));
		int resizedWidth=Math.max(1, (int) Math.round(img.getWidth()*factor));
		VipsImage resized=img.resize(resizedWidth/(double) img.getWidth(), resizedFrameHeight/(double) frameHeight);
		try{
			if(resized.getHeight()!=resizedFrameHeight*frameCount)
				throw new IOException("Resized animation height "+resized.getHeight()+" is not "+frameCount+" frames of "+resizedFrameHeight+" pixels");
			resized.setFrameHeight(resizedFrameHeight);
			resized.writeToFile(file.getAbsolutePath()+"["+String.join(",", args)+"]");
			outSize[0]=resized.getWidth();
			outSize[1]=resizedFrameHeight;
		}finally{
			resized.release();
		}
	}

	private static ArrayList<String> saveArgs(boolean lossless){
		ArrayList<String> args=new ArrayList<>();
		args.add(lossless ? "lossless=true" : "Q="+QUALITY_PHOTO);
		return args;
	}

	/**
	 * Removes all metadata except the colour profile and, for animations, the fields that describe the animation.
	 */
	private static void keepOnlyFields(VipsImage img, boolean animated){
		for(String key:img.getFields()){
			if("icc-profile-data".equals(key) || (animated && ANIMATION_METADATA_FIELDS.contains(key)))
				continue;
			img.removeField(key);
		}
	}
}
