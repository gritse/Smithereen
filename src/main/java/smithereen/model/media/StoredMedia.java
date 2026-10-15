package smithereen.model.media;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

import smithereen.model.SizedImage;
import smithereen.storage.media.MediaFileStorageDriver;

/**
 * A resolved media file: the reference to it plus its record from the database, and the logic to render that kind of file.
 * Created by {@link #create} from the file's type.
 */
public sealed interface StoredMedia permits SizedImage, Video{
	/**
	 * @return the reference this media was resolved from, which carries alt text, rotation and the avatar crop
	 */
	@NotNull
	MediaFileRef ref();

	/**
	 * @return the file's record, or null for an unresolved reference (the file may have been deleted)
	 */
	@Nullable
	MediaFileRecord record();

	@NotNull
	MediaKind kind();

	default long fileID(){
		return ref().fileID;
	}

	/**
	 * @return the URL of the stored file itself, served by the web server or the object storage, or null if unresolved
	 */
	@Nullable
	default URI getFileURL(){
		MediaFileRecord record=record();
		return record==null ? null : MediaFileStorageDriver.getInstance().getFilePublicURL(record.id());
	}

	/**
	 * @return the stored width (before rotation), or 0 if unresolved
	 */
	default int getStoredWidth(){
		MediaFileRecord record=record();
		return record==null ? 0 : record.metadata().width();
	}

	default int getStoredHeight(){
		MediaFileRecord record=record();
		return record==null ? 0 : record.metadata().height();
	}

	@NotNull
	static StoredMedia create(@NotNull MediaFileRef ref, @NotNull MediaFileRecord record){
		return switch(record.id().type().kind()){
			case STILL_IMAGE -> new StillImage(ref, record);
			case ANIMATED_IMAGE -> new AnimatedImage(ref, record);
			case VIDEO -> new Video(ref, record);
			case ARCHIVE -> throw new IllegalArgumentException("File "+record.id()+" is not media");
		};
	}
}
