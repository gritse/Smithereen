package smithereen.media;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

import smithereen.model.attachments.GraffitiAttachment;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaKind;

/**
 * How an upload is to be processed. Usually taken from the purpose, with a few presets for special uploads.
 *
 * @param maxSize the longest side of a still image or an animation frame is scaled down to this
 * @param stillImageType the type a still image is stored as
 * @param lossless whether still images are encoded losslessly (graffiti)
 * @param exactWidth if non-zero, still images must have exactly this width
 * @param exactHeight if non-zero, still images must have exactly this height
 * @param requireSquare whether still images must be square (app logos)
 * @param minSize if non-zero, the shorter side of a still image must be at least this
 * @param maxAnimationPixels budget for the total number of pixels across the frames of an animation
 * @param acceptedKinds overrides the purpose's accepted kinds when not null
 */
public record ProcessingOptions(int maxSize, MediaFileType stillImageType, boolean lossless, int exactWidth, int exactHeight, boolean requireSquare, int minSize,
								long maxAnimationPixels, @Nullable Set<MediaKind> acceptedKinds){
	public static final int DEFAULT_MAX_SIZE=2560;
	/**
	 * Encoding an animation takes time proportional to its total number of pixels, so longer or bigger ones are scaled down to fit this budget.
	 */
	public static final long DEFAULT_MAX_ANIMATION_PIXELS=50_000_000L;

	public static final ProcessingOptions DEFAULT=new ProcessingOptions(DEFAULT_MAX_SIZE, MediaFileType.IMAGE_PHOTO, false, 0, 0, false, 0, DEFAULT_MAX_ANIMATION_PIXELS, null);
	public static final ProcessingOptions GRAFFITI=new ProcessingOptions(DEFAULT_MAX_SIZE, MediaFileType.IMAGE_GRAFFITI, true, GraffitiAttachment.WIDTH, GraffitiAttachment.HEIGHT, false, 0, DEFAULT_MAX_ANIMATION_PIXELS, Set.of(MediaKind.STILL_IMAGE));
	public static final ProcessingOptions APP_LOGO=new ProcessingOptions(400, MediaFileType.IMAGE_PHOTO, false, 0, 0, true, 200, DEFAULT_MAX_ANIMATION_PIXELS, null);

	public ProcessingOptions withMaxAnimationPixels(long maxAnimationPixels){
		return new ProcessingOptions(maxSize, stillImageType, lossless, exactWidth, exactHeight, requireSquare, minSize, maxAnimationPixels, acceptedKinds);
	}
}
