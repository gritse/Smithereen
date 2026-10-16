package smithereen.model.media;

import java.util.EnumSet;
import java.util.Set;

/**
 * What an upload is for. Decides which kinds of media are accepted and is part of the upload token, so that
 * a file uploaded for one purpose cannot be used for another.
 */
public enum MediaFileUploadPurpose{
	/**
	 * Posts, comments and private messages
	 */
	ATTACHMENT(EnumSet.of(MediaKind.STILL_IMAGE, MediaKind.ANIMATED_IMAGE, MediaKind.VIDEO, MediaKind.AUDIO)),
	ALBUM_PHOTO(EnumSet.of(MediaKind.STILL_IMAGE, MediaKind.ANIMATED_IMAGE)),
	/**
	 * User and group profile pictures: still images only
	 */
	AVATAR(EnumSet.of(MediaKind.STILL_IMAGE)),
	APP_LOGO(EnumSet.of(MediaKind.STILL_IMAGE));

	private final Set<MediaKind> acceptedKinds;

	MediaFileUploadPurpose(Set<MediaKind> acceptedKinds){
		this.acceptedKinds=acceptedKinds;
	}

	public Set<MediaKind> acceptedKinds(){
		return acceptedKinds;
	}

	public boolean accepts(MediaKind kind){
		return acceptedKinds.contains(kind);
	}
}
