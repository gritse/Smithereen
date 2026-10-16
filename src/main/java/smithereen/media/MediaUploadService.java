package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Set;

import smithereen.Config;
import smithereen.model.Account;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileRef;
import smithereen.model.media.MediaFileReferenceType;
import smithereen.model.media.MediaFileStatus;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.media.MediaKind;
import smithereen.model.media.Video;
import smithereen.model.media.VideoMetadata;
import smithereen.storage.MediaFileResolver;
import smithereen.storage.MediaStorage;
import smithereen.storage.media.MediaFileStorageDriver;

/**
 * The upload pipeline, independent of HTTP: sniff the kind, check it against the purpose, process, store, record.
 */
public final class MediaUploadService{
	private static final Logger LOG=LoggerFactory.getLogger(MediaUploadService.class);

	private MediaUploadService(){}

	/**
	 * @return the largest file accepted for this kind of media
	 */
	public static long maxUploadSize(MediaKind kind){
		return switch(kind){
			case VIDEO -> Config.videoMaxUploadSize;
			case AUDIO -> Config.audioMaxUploadSize;
			default -> Config.imageMaxUploadSize;
		};
	}

	/**
	 * @return the largest file a purpose accepts, whatever the kind
	 */
	public static long maxUploadSize(MediaFileUploadPurpose purpose){
		return purpose.acceptedKinds().stream().mapToLong(MediaUploadService::maxUploadSize).max().orElse(Config.imageMaxUploadSize);
	}

	/**
	 * Processes and stores an uploaded file.
	 * @param input the uploaded file; it is not deleted
	 * @param declaredMimeType the Content-Type the client sent
	 * @return a resolved reference to the new media file
	 * @throws MediaValidationException if the file is not accepted
	 */
	public static MediaFileRef store(File input, String declaredMimeType, Account self, MediaFileUploadPurpose purpose, ProcessingOptions options, MediaProcessingQueue queue) throws MediaValidationException, IOException, SQLException{
		// Anything that isn't declared as a video or audio is held to the image limit before it is even looked at
		if((declaredMimeType==null || !(declaredMimeType.startsWith("video/") || declaredMimeType.startsWith("audio/"))) && input.length()>Config.imageMaxUploadSize)
			throw MediaValidationException.tooLarge(Config.imageMaxUploadSize);
		MediaKind kind=MediaSniffer.sniff(input, declaredMimeType);
		Set<MediaKind> accepted=options.acceptedKinds()!=null ? options.acceptedKinds() : purpose.acceptedKinds();
		if(!accepted.contains(kind))
			throw notAllowed(kind);
		if(input.length()>maxUploadSize(kind))
			throw MediaValidationException.tooLarge(maxUploadSize(kind));

		if(kind==MediaKind.VIDEO)
			return storeVideo(input, self, options, queue);
		if(kind==MediaKind.AUDIO)
			return storeAudio(input, self, queue);

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

	/**
	 * Videos are stored in two steps: the poster and the record go in now, with status PROCESSING, and the original
	 * is moved to the spool directory for the queue to transcode.
	 * @param input moved to the spool directory on success
	 */
	private static MediaFileRef storeVideo(File input, Account self, ProcessingOptions options, MediaProcessingQueue queue) throws MediaValidationException, IOException, SQLException{
		VideoProcessor.Prepared prepared=new VideoProcessor().prepare(input, options);
		try{
			ProcessedMedia poster=prepared.poster();
			MediaFileRecord posterRecord=MediaStorage.createMediaFileRecord(poster.type(), poster.file().length(), self.user.id, poster.metadata());
			MediaFileStorageDriver.getInstance().storeFile(poster.file(), posterRecord.id(), false);

			VideoMetadata meta=new VideoMetadata(prepared.width(), prepared.height(), prepared.durationSeconds(), posterRecord.id().id(), prepared.blurhash(), prepared.frameRate());
			MediaFileRecord record=MediaStorage.createMediaFileRecord(MediaFileType.VIDEO, input.length(), self.user.id, meta, MediaFileStatus.PROCESSING);
			MediaStorage.createMediaFileReference(posterRecord.id().id(), record.id().id(), MediaFileReferenceType.VIDEO_POSTER, self.user.id);
			MediaFileRef ref=spoolForProcessing(record, input, queue);
			MediaFileResolver.resolve(((Video) ref.media()).posterRef);
			LOG.debug("Queued video upload by {} as file {} ({}x{}, {} s)", self.user.id, record.id().id(), prepared.width(), prepared.height(), prepared.durationSeconds());
			return ref;
		}finally{
			prepared.poster().deleteFiles();
		}
	}

	private static MediaFileRef storeAudio(File input, Account self, MediaProcessingQueue queue) throws MediaValidationException, IOException, SQLException{
		AudioProcessor.Prepared prepared=new AudioProcessor().prepare(input);
		MediaFileRecord record=MediaStorage.createMediaFileRecord(MediaFileType.AUDIO, input.length(), self.user.id, prepared.metadata(), MediaFileStatus.PROCESSING);
		MediaFileRef ref=spoolForProcessing(record, input, queue);
		LOG.debug("Queued audio upload by {} as file {} ({} s)", self.user.id, record.id().id(), prepared.durationSeconds());
		return ref;
	}

	/**
	 * Hands a file with a PROCESSING record to the queue: the job row goes in first, then the original is moved to the
	 * spool directory, so that a record without a job (which would never be retried or cleaned up) can't be left behind.
	 */
	private static MediaFileRef spoolForProcessing(MediaFileRecord record, File input, MediaProcessingQueue queue) throws IOException, SQLException{
		File spool=MediaProcessingQueue.spoolFile(record.id().id());
		MediaStorage.createProcessingJob(record.id().id(), spool.getAbsolutePath());
		try{
			Files.move(input.toPath(), spool.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}catch(IOException x){
			MediaStorage.deleteProcessingJob(record.id().id());
			MediaStorage.updateMediaFileStatus(record.id().id(), MediaFileStatus.FAILED, 0);
			throw x;
		}
		queue.enqueue(record.id().id());
		MediaFileRef ref=new MediaFileRef(record.id().id());
		ref.fillIn(record);
		return ref;
	}

	private static MediaValidationException notAllowed(MediaKind kind){
		return switch(kind){
			case ANIMATED_IMAGE -> new MediaValidationException(415, "err_file_upload_animated_not_allowed");
			case VIDEO -> new MediaValidationException(415, "err_file_upload_video_not_allowed");
			case AUDIO -> new MediaValidationException(415, "err_file_upload_audio_not_allowed");
			default -> MediaValidationException.unsupportedFormat();
		};
	}
}
