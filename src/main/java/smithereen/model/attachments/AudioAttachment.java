package smithereen.model.attachments;

import smithereen.model.media.Audio;

/**
 * Audio has no size, so it is listed under the tiled grid rather than in it.
 */
public final class AudioAttachment extends Attachment{
	public Audio audio;

	@Override
	public Type getType(){
		return Type.AUDIO;
	}
}
