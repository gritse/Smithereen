package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

import smithereen.model.Account;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileRef;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.media.MediaKind;
import smithereen.storage.MediaStorage;
import smithereen.storage.media.MediaFileStorageDriver;

/**
 * The upload pipeline, independent of HTTP: sniff the kind, check it against the purpose, process, store, record.
 */
public final class MediaUploadService{
	private static final Logger LOG=LoggerFactory.getLogger(MediaUploadService.class);
	public static final long MAX_IMAGE_SIZE=10*1024*1024;

	private MediaUploadService(){}

	/**
	 * @return the largest file accepted for this kind of media
	 */
	public static long maxUploadSize(MediaKind kind){
		return MAX_IMAGE_SIZE;
	}

	/**
	 * @return the largest file a purpose accepts, whatever the kind
	 */
	public static long maxUploadSize(MediaFileUploadPurpose purpose){
		return purpose.acceptedKinds().stream().mapToLong(MediaUploadService::maxUploadSize).max().orElse(MAX_IMAGE_SIZE);
	}

	/**
	 * Processes and stores an uploaded file.
	 * @param input the uploaded file; it is not deleted
	 * @param declaredMimeType the Content-Type the client sent
	 * @return a resolved reference to the new media file
	 * @throws MediaValidationException if the file is not accepted
	 */
	public static MediaFileRef store(File input, String declaredMimeType, Account self, MediaFileUploadPurpose purpose, ProcessingOptions options) throws MediaValidationException, IOException, SQLException{
		MediaKind kind=MediaSniffer.sniff(input, declaredMimeType);
		Set<MediaKind> accepted=options.acceptedKinds()!=null ? options.acceptedKinds() : purpose.acceptedKinds();
		if(!accepted.contains(kind))
			throw notAllowed(kind);
		if(input.length()>maxUploadSize(kind))
			throw MediaValidationException.tooLarge(maxUploadSize(kind));

		ProcessedMedia processed=MediaProcessor.forKind(kind).process(input, options);
		try{
			MediaFileRecord record=MediaStorage.createMediaFileRecord(processed.type(), processed.file().length(), self.user.id, processed.metadata());
			MediaFileStorageDriver.getInstance().storeFile(processed.file(), record.id(), false);
			MediaFileRef ref=new MediaFileRef(record.id().id());
			ref.fillIn(record);
			LOG.debug("Stored {} upload by {} as file {}", kind, self.user.id, record.id().id());
			return ref;
		}finally{
			processed.deleteFiles();
		}
	}

	private static MediaValidationException notAllowed(MediaKind kind){
		return switch(kind){
			case ANIMATED_IMAGE -> new MediaValidationException(415, "err_file_upload_animated_not_allowed");
			default -> MediaValidationException.unsupportedFormat();
		};
	}
}
