package smithereen.model.media;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An audio file: AAC in M4A served as the stored file. Like a video, it is transcoded on the processing queue after the upload.
 */
public final class Audio implements StoredMedia{
	private final MediaFileRef ref;
	private final MediaFileRecord record;
	private final AudioMetadata metadata;

	public Audio(@NotNull MediaFileRef ref, @NotNull MediaFileRecord record){
		this.ref=ref;
		this.record=record;
		this.metadata=(AudioMetadata) record.metadata();
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
		return MediaKind.AUDIO;
	}

	/**
	 * @return duration in seconds
	 */
	public int getDuration(){
		return metadata.duration();
	}

	@Nullable
	public String getTitle(){
		return metadata.title();
	}

	@Nullable
	public String getArtist(){
		return metadata.artist();
	}

	public MediaFileStatus getStatus(){
		return record.status();
	}

	public boolean isReady(){
		return record.status()==MediaFileStatus.READY;
	}
}
