package smithereen.storage;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import smithereen.LruCache;
import smithereen.Utils;
import smithereen.model.media.MediaFileID;
import smithereen.model.media.MediaFileMetadata;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileReferenceType;
import smithereen.model.media.MediaFileStatus;
import smithereen.model.media.MediaFileType;
import smithereen.storage.sql.SQLQueryBuilder;

public class MediaStorage{
	private static final LruCache<Long, MediaFileRecord> recordCache=new LruCache<>(5000);

	public static Map<Long, MediaFileRecord> getMediaFileRecords(Collection<Long> ids) throws SQLException{
		if(ids.isEmpty())
			return Map.of();
		HashMap<Long, MediaFileRecord> res=new HashMap<>();
		Set<Long> needIDs=new HashSet<>();
		for(long id:ids){
			MediaFileRecord r=recordCache.get(id);
			if(r==null)
				needIDs.add(id);
			else
				res.put(id, r);
		}
		if(needIDs.isEmpty())
			return res;
		new SQLQueryBuilder()
				.selectFrom("media_files")
				.columns("id", "random_id", "size", "type", "created_at", "metadata", "original_owner_id", "status")
				.whereIn("id", needIDs)
				.executeAsStream(MediaFileRecord::fromResultSet)
				.forEach(r->{
					res.put(r.id().id(), r);
					cacheIfReady(r);
				});
		return res;
	}

	public static MediaFileRecord getMediaFileRecord(long id) throws SQLException{
		MediaFileRecord r=recordCache.get(id);
		if(r!=null)
			return r;
		r=new SQLQueryBuilder()
				.selectFrom("media_files")
				.columns("id", "random_id", "size", "type", "created_at", "metadata", "original_owner_id", "status")
				.where("id=?", id)
				.executeAndGetSingleObject(MediaFileRecord::fromResultSet);
		if(r!=null)
			cacheIfReady(r);
		return r;
	}

	/**
	 * A record whose status can still change is never cached, so that a page that read it just before the
	 * processing queue updated it can't put the stale copy back after the queue's cache removal.
	 */
	private static void cacheIfReady(MediaFileRecord r){
		if(r.status()==MediaFileStatus.READY)
			recordCache.put(r.id().id(), r);
	}

	public static MediaFileRecord createMediaFileRecord(MediaFileType type, long fileSize, int ownerID, MediaFileMetadata metadata) throws SQLException{
		return createMediaFileRecord(type, fileSize, ownerID, metadata, MediaFileStatus.READY);
	}

	public static MediaFileRecord createMediaFileRecord(MediaFileType type, long fileSize, int ownerID, MediaFileMetadata metadata, MediaFileStatus status) throws SQLException{
		byte[] randomID=Utils.randomBytes(18);
		long id=new SQLQueryBuilder()
				.insertInto("media_files")
				.value("random_id", randomID)
				.value("size", fileSize)
				.value("type", type)
				.value("metadata", Utils.gson.toJson(metadata))
				.value("original_owner_id", ownerID)
				.value("status", status)
				.executeAndGetIDLong();
		MediaFileRecord mfr=new MediaFileRecord(
				new MediaFileID(id, randomID, ownerID, type),
				fileSize, Instant.now(), metadata, status
		);
		cacheIfReady(mfr);
		return mfr;
	}

	/**
	 * Reads a record from the database even if it is cached, for the processing queue which needs the current status.
	 */
	public static MediaFileRecord getMediaFileRecordUncached(long id) throws SQLException{
		recordCache.remove(id);
		return getMediaFileRecord(id);
	}

	public static void updateMediaFileStatus(long id, MediaFileStatus status, long size) throws SQLException{
		SQLQueryBuilder q=new SQLQueryBuilder()
				.update("media_files")
				.where("id=?", id)
				.value("status", status);
		if(size>0)
			q.value("size", size);
		q.executeNoResult();
		recordCache.remove(id);
	}

	public record ProcessingJob(long fileID, String sourcePath, int attempts){}

	public static void createProcessingJob(long fileID, String sourcePath) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("media_processing_jobs")
				.value("file_id", fileID)
				.value("source_path", sourcePath)
				.executeNoResult();
	}

	public static List<ProcessingJob> getProcessingJobs() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("media_processing_jobs")
				.orderBy("created_at ASC")
				.executeAsStream(res->new ProcessingJob(res.getLong("file_id"), res.getString("source_path"), res.getInt("attempts")))
				.toList();
	}

	public static void incrementProcessingJobAttempts(long fileID) throws SQLException{
		new SQLQueryBuilder()
				.update("media_processing_jobs")
				.where("file_id=?", fileID)
				.valueExpr("attempts", "attempts+1")
				.executeNoResult();
	}

	public static void deleteProcessingJob(long fileID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("media_processing_jobs")
				.where("file_id=?", fileID)
				.executeNoResult();
	}

	public static void createMediaFileReference(long fileID, long objectID, MediaFileReferenceType type, int ownerID) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("media_file_refs")
				.value("file_id", fileID)
				.value("object_id", objectID)
				.value("object_type", type)
				.value(ownerID<0 ? "owner_group_id" : "owner_user_id", ownerID!=0 ? Math.abs(ownerID) : null)
				.executeNoResult();
	}

	public static void deleteMediaFileReferences(long objectID, MediaFileReferenceType type) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("media_file_refs")
				.where("object_id=? AND object_type=?", objectID, type)
				.executeNoResult();
	}

	public static void deleteMediaFileReference(long objectID, MediaFileReferenceType type, long fileID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("media_file_refs")
				.where("object_id=? AND object_type=? AND file_id=?", objectID, type, fileID)
				.executeNoResult();
	}

	public static void deleteMediaFileReferences(Collection<Long> objectIDs, MediaFileReferenceType type) throws SQLException{
		if(objectIDs.isEmpty())
			return;
		new SQLQueryBuilder()
				.deleteFrom("media_file_refs")
				.whereIn("object_id", objectIDs)
				.andWhere("object_type=?", type)
				.executeNoResult();
	}

	public static List<MediaFileRecord> getUnreferencedMediaFileRecords() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("media_files")
				.where("ref_count=0 AND status<>? AND created_at<(CURRENT_TIMESTAMP()-INTERVAL 1 DAY)", MediaFileStatus.PROCESSING)
				.executeAsStream(MediaFileRecord::fromResultSet)
				.toList();
	}

	public static void deleteMediaFileRecords(Collection<Long> ids) throws SQLException{
		if(ids.isEmpty())
			return;
		new SQLQueryBuilder()
				.deleteFrom("media_files")
				.whereIn("id", ids)
				.executeNoResult();
		// A deleted video no longer needs its poster, which becomes unreferenced and is deleted on a later pass
		new SQLQueryBuilder()
				.deleteFrom("media_file_refs")
				.whereIn("object_id", ids)
				.andWhere("object_type=?", MediaFileReferenceType.VIDEO_POSTER)
				.executeNoResult();
		for(long id:ids)
			recordCache.remove(id);
	}
}
