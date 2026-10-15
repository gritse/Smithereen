package smithereen.media;

import java.io.File;
import java.util.List;

import smithereen.model.media.MediaFileMetadata;
import smithereen.model.media.MediaFileType;

/**
 * The output of a {@link MediaProcessor}: the file to store, what to store it as, and its metadata.
 *
 * @param file a temporary file; the caller stores it and deletes it
 * @param derived further files produced from the same upload that must be stored too, such as a video's poster frame
 */
public record ProcessedMedia(File file, MediaFileType type, MediaFileMetadata metadata, List<ProcessedMedia> derived){
	public ProcessedMedia(File file, MediaFileType type, MediaFileMetadata metadata){
		this(file, type, metadata, List.of());
	}

	public void deleteFiles(){
		file.delete();
		for(ProcessedMedia d:derived)
			d.deleteFiles();
	}
}
