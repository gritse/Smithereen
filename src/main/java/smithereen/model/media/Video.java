package smithereen.model.media;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import smithereen.model.SizedImage;

/**
 * A video: an MP4 served as the stored file, with a poster frame that is a still image of its own.
 * While the video is being transcoded the poster is already there and the file is not.
 */
public final class Video implements StoredMedia{
	private final MediaFileRef ref;
	private final MediaFileRecord record;
	private final VideoMetadata metadata;
	/**
	 * Resolved by {@link smithereen.storage.MediaFileResolver} together with the video itself.
	 */
	public final MediaFileRef posterRef;

	public Video(@NotNull MediaFileRef ref, @NotNull MediaFileRecord record){
		this.ref=ref;
		this.record=record;
		this.metadata=(VideoMetadata) record.metadata();
		posterRef=metadata.posterFileID()!=0 ? new MediaFileRef(metadata.posterFileID()) : null;
	}

	@Override
	@NotNull
	public MediaFileRef ref(){
		return ref;
	}

	@Override
	@NotNull
	public MediaFileRecord record(){
		return record;
	}

	@Override
	@NotNull
	public MediaKind kind(){
		return MediaKind.VIDEO;
	}

	public int getWidth(){
		return metadata.width();
	}

	public int getHeight(){
		return metadata.height();
	}

	/**
	 * @return duration in seconds
	 */
	public int getDuration(){
		return metadata.duration();
	}

	public MediaFileStatus getStatus(){
		return record.status();
	}

	public boolean isReady(){
		return record.status()==MediaFileStatus.READY;
	}

	/**
	 * @return the poster frame, or null if the video has none
	 */
	@Nullable
	public SizedImage getPoster(){
		return posterRef!=null ? posterRef.image() : null;
	}

	public String getBlurHash(){
		return metadata.blurhash();
	}
}
