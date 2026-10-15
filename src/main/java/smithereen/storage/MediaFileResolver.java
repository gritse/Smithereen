package smithereen.storage;

import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import smithereen.model.AttachmentHostContentObject;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileRef;
import smithereen.model.objects.Actor;

/**
 * The one place that turns {@link MediaFileRef}s into resolved media: fetches the file records for a batch of
 * references in one query and fills each reference in. References that are already resolved are skipped,
 * references to deleted files stay unresolved and render as broken pictures.
 */
public final class MediaFileResolver{
	private MediaFileResolver(){}

	public static void resolve(@Nullable MediaFileRef ref) throws SQLException{
		if(ref==null || ref.isResolved() || ref.fileID==0)
			return;
		MediaFileRecord record=MediaStorage.getMediaFileRecord(ref.fileID);
		if(record!=null)
			ref.fillIn(record);
	}

	public static void resolve(Collection<MediaFileRef> refs) throws SQLException{
		Set<Long> needIDs=new HashSet<>();
		for(MediaFileRef ref:refs){
			if(ref!=null && !ref.isResolved() && ref.fileID!=0)
				needIDs.add(ref.fileID);
		}
		if(needIDs.isEmpty())
			return;
		Map<Long, MediaFileRecord> records=MediaStorage.getMediaFileRecords(needIDs);
		for(MediaFileRef ref:refs){
			if(ref==null || ref.isResolved())
				continue;
			MediaFileRecord record=records.get(ref.fileID);
			if(record!=null)
				ref.fillIn(record);
		}
	}

	/**
	 * Resolves the attachments of posts, comments or messages.
	 */
	public static void resolveAttachments(Collection<? extends AttachmentHostContentObject> objects) throws SQLException{
		resolve(objects.stream()
				.map(AttachmentHostContentObject::getAttachments)
				.filter(Objects::nonNull)
				.flatMap(List::stream)
				.toList());
	}

	/**
	 * Resolves the avatars of users or groups.
	 */
	public static void resolveAvatars(Collection<? extends Actor> actors) throws SQLException{
		resolve(actors.stream()
				.map(a->a.avatar)
				.filter(Objects::nonNull)
				.toList());
	}
}
