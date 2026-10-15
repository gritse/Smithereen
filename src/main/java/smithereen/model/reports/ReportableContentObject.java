package smithereen.model.reports;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

import smithereen.model.objects.StoredObject;
import smithereen.model.media.MediaFileRef;
import smithereen.model.MailMessage;
import smithereen.model.OwnedContentObject;
import smithereen.model.Post;
import smithereen.model.comments.Comment;
import smithereen.model.photos.Photo;
import smithereen.storage.MediaStorageUtils;
import smithereen.util.JsonArrayBuilder;

public sealed interface ReportableContentObject extends OwnedContentObject permits Post, MailMessage, Photo, Comment{
	JsonObject serializeForReport(int targetID, Set<Long> outFileIDs);
	void fillFromReport(int reportID, JsonObject jo);
	ReportableContentObjectID getReportableObjectID();

	static JsonArray serializeMediaAttachments(List<MediaFileRef> attachments, Set<Long> outFileIDs){
		JsonArrayBuilder jb=new JsonArrayBuilder();
		for(MediaFileRef att:attachments){
			jb.add(att.toJson());
			outFileIDs.add(att.fileID);
		}
		return jb.build();
	}
}
