package smithereen.model.attachments;

import org.jetbrains.annotations.Nullable;

import smithereen.model.BlurHashable;
import smithereen.model.media.Video;

public final class VideoAttachment extends Attachment implements SizedAttachment, BlurHashable{
	public Video video;
	private String blurHash;

	@Override
	public int getWidth(){
		return video.getWidth();
	}

	@Override
	public int getHeight(){
		return video.getHeight();
	}

	@Override
	public boolean isSizeKnown(){
		return video.getWidth()>0 && video.getHeight()>0;
	}

	@Override
	public Type getType(){
		return Type.VIDEO;
	}

	@Override
	@Nullable
	public String getBlurHash(){
		return blurHash;
	}

	@Override
	public void setBlurHash(@Nullable String blurHash){
		this.blurHash=blurHash;
	}
}
