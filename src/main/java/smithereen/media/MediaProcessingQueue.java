package smithereen.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.lang.Lang;
import smithereen.model.Account;
import smithereen.model.User;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileRef;
import smithereen.model.media.MediaFileStatus;
import smithereen.model.media.Video;
import smithereen.model.media.VideoMetadata;
import smithereen.storage.MediaFileResolver;
import smithereen.storage.MediaStorage;
import smithereen.storage.media.MediaFileStorageDriver;
import smithereen.templates.VideoRenderer;
import smithereen.util.JsonObjectBuilder;

/**
 * Runs the slow part of media processing, transcoding, outside the upload request. Jobs are rows in
 * media_processing_jobs so that they survive a restart; the uploaded original waits in a spool directory under
 * the upload path. When a job finishes, the file's status changes and the owner's open pages are told over
 * the notifier websocket so that they can swap the placeholder for the player.
 */
public final class MediaProcessingQueue{
	private static final Logger LOG=LoggerFactory.getLogger(MediaProcessingQueue.class);
	/**
	 * A job that fails this many times (including interrupted attempts from a restart) is given up on.
	 */
	private static final int MAX_ATTEMPTS=2;

	private final ApplicationContext context;
	private final ExecutorService executor;
	/**
	 * Files queued or being processed, so that a job is never run twice at once (an upload during startup is
	 * enqueued by the upload and again by {@link #start}).
	 */
	private final Set<Long> inFlight=ConcurrentHashMap.newKeySet();

	public MediaProcessingQueue(ApplicationContext context){
		this.context=context;
		AtomicInteger counter=new AtomicInteger();
		executor=Executors.newFixedThreadPool(Math.max(1, Config.mediaProcessingThreads), r->{
			Thread t=new Thread(r, "MediaProcessing-"+counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	public static File spoolDirectory(){
		File dir=new File(Config.uploadPath, "spool");
		if(!dir.exists() && !dir.mkdirs())
			LOG.warn("Could not create the spool directory {}", dir);
		return dir;
	}

	/**
	 * @return where the uploaded original of a file waits for processing
	 */
	public static File spoolFile(long fileID){
		return new File(spoolDirectory(), fileID+".src");
	}

	/**
	 * Picks up the jobs left over from before a restart.
	 */
	public void start(){
		try{
			List<MediaStorage.ProcessingJob> jobs=MediaStorage.getProcessingJobs();
			if(!jobs.isEmpty())
				LOG.info("Resuming {} media processing job(s)", jobs.size());
			for(MediaStorage.ProcessingJob job:jobs){
				if(job.attempts()>=MAX_ATTEMPTS)
					executor.submit(()->fail(job.fileID(), "gave up after "+job.attempts()+" attempts"));
				else
					enqueue(job.fileID());
			}
		}catch(SQLException x){
			LOG.error("Failed to load media processing jobs", x);
		}
	}

	public void enqueue(long fileID){
		if(!inFlight.add(fileID))
			return;
		executor.submit(()->{
			try{
				run(fileID);
			}finally{
				inFlight.remove(fileID);
			}
		});
	}

	public void shutDown(){
		executor.shutdownNow();
		try{
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}catch(InterruptedException ignore){
			Thread.currentThread().interrupt();
		}
	}

	private void run(long fileID){
		File source=spoolFile(fileID);
		File output=null;
		try{
			MediaStorage.incrementProcessingJobAttempts(fileID);
			MediaFileRecord record=MediaStorage.getMediaFileRecordUncached(fileID);
			if(record==null){
				LOG.warn("Media file {} is gone, dropping its processing job", fileID);
				MediaStorage.deleteProcessingJob(fileID);
				source.delete();
				return;
			}
			if(record.status()!=MediaFileStatus.PROCESSING){
				MediaStorage.deleteProcessingJob(fileID);
				source.delete();
				return;
			}
			if(!source.exists()){
				fail(fileID, "source file "+source+" is missing");
				return;
			}
			if(!(record.metadata() instanceof VideoMetadata meta))
				throw new IllegalStateException("Only videos are processed on the queue, got "+record.id().type());
			LOG.info("Transcoding video file {} ({} bytes)", fileID, source.length());
			output=new VideoProcessor().transcode(source, meta.width(), meta.height(), meta.frameRate());
			long size=output.length();
			MediaFileStorageDriver.getInstance().storeFile(output, record.id(), false);
			MediaStorage.updateMediaFileStatus(fileID, MediaFileStatus.READY, size);
			MediaStorage.deleteProcessingJob(fileID);
			source.delete();
			LOG.info("Video file {} is ready ({} bytes)", fileID, size);
			notifyOwner(fileID, record.id().originalOwnerID());
		}catch(Throwable x){
			LOG.error("Processing media file {} failed", fileID, x);
			if(output!=null)
				output.delete();
			fail(fileID, x.toString());
		}
	}

	private void fail(long fileID, String reason){
		try{
			MediaFileRecord record=MediaStorage.getMediaFileRecordUncached(fileID);
			MediaStorage.updateMediaFileStatus(fileID, MediaFileStatus.FAILED, 0);
			MediaStorage.deleteProcessingJob(fileID);
			spoolFile(fileID).delete();
			LOG.warn("Media file {} marked as failed: {}", fileID, reason);
			if(record!=null)
				notifyOwner(fileID, record.id().originalOwnerID());
		}catch(SQLException x){
			LOG.error("Failed to mark media file {} as failed", fileID, x);
		}
	}

	/**
	 * Sends the owner's open pages the new HTML of the attachment.
	 */
	private void notifyOwner(long fileID, int ownerID){
		if(ownerID<=0)
			return;
		try{
			User owner=context.getUsersController().getUserOrThrow(ownerID);
			Account account=context.getUsersController().getAccountForUser(owner);
			Lang lang=Lang.get(account!=null && account.prefs.locale!=null ? account.prefs.locale : Locale.US);
			MediaFileRef ref=new MediaFileRef(fileID);
			MediaFileResolver.resolve(ref);
			if(!(ref.media() instanceof Video video))
				return;
			String html=VideoRenderer.render(video, null, lang);
			String message=new JsonObjectBuilder()
					.add("type", "mediaReady")
					.add("fileID", video.record().id().getEncodedID())
					.add("status", video.getStatus().toString())
					.add("html", html)
					.build()
					.toString();
			context.getNotificationsController().sendRawToUser(ownerID, message);
		}catch(Exception x){
			LOG.warn("Failed to notify user {} about media file {}", ownerID, fileID, x);
		}
	}
}
