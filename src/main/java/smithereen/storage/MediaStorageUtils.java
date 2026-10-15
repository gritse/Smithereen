package smithereen.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import smithereen.ApplicationContext;
import smithereen.Utils;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.lang.Lang;
import smithereen.media.MediaUploadService;
import smithereen.media.MediaValidationException;
import smithereen.media.ProcessingOptions;
import smithereen.model.Account;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.User;
import smithereen.model.media.MediaFileID;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileRef;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.media.MediaFileUploadTokens;
import smithereen.model.photos.Photo;
import smithereen.storage.media.MediaFileStorageDriver;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.util.XTEA;
import spark.Request;
import spark.Response;
import spark.Spark;

import static smithereen.Utils.lang;

public class MediaStorageUtils{
	private static final Logger LOG=LoggerFactory.getLogger(MediaStorageUtils.class);

	/**
	 * Resolves the attachment IDs a client sent with a post, comment or message into media file references.
	 * IDs are either "photo:&lt;photo ID&gt;" for album photos or "&lt;file ID&gt;:&lt;upload token&gt;" for uploads.
	 */
	public static void fillAttachmentObjects(ApplicationContext context, User self, List<MediaFileRef> attachObjects, List<String> attachmentIDs, Map<String, String> altTexts, int attachmentCount, int maxAttachments, boolean allowGraffiti) throws SQLException{
		for(String id:attachmentIDs){
			String[] idParts=id.split(":");
			if(idParts.length!=2)
				continue;

			if("photo".equals(idParts[0])){
				long photoID=XTEA.decodeObjectID(idParts[1], ObfuscatedObjectIDType.PHOTO);
				Photo photo=context.getPhotosController().getPhotoIgnoringPrivacy(photoID);
				if(photo.localFileID==0){
					LOG.debug("Not attaching photo {} to {}'s post because it has no local file", photoID, self.username);
					continue;
				}
				try{
					context.getPrivacyController().enforceObjectPrivacy(self, photo);
				}catch(UserActionNotAllowedException x){
					LOG.debug("Not attaching photo {} to {}'s post because they can't access it", photoID, self.username);
					continue;
				}
				MediaFileRef img=new MediaFileRef(photo.localFileID);
				img.photoID=photoID;
				if(photo.metadata!=null)
					img.rotation=photo.metadata.rotation;
				MediaFileResolver.resolve(img);
				attachObjects.add(img);
			}else{
				MediaFileRef img=getMediaFileRef(id, MediaFileUploadPurpose.ATTACHMENT, self.id);
				if(img==null || (img.isGraffiti() && !allowGraffiti))
					continue;
				img.name=altTexts.get(id);
				attachObjects.add(img);
			}
			attachmentCount++;
			if(attachmentCount==maxAttachments)
				break;
		}
	}

	/**
	 * Looks up an uploaded file by its client-side ID, checking the upload token for the purpose and owner.
	 * @return the resolved reference, or null if the ID or the token is invalid
	 */
	public static MediaFileRef getMediaFileRef(String id, MediaFileUploadPurpose expectedPurpose, int expectedOwnerID) throws SQLException{
		String[] idParts=id.split(":");
		if(idParts.length!=2)
			return null;
		long fileID=XTEA.decodeObjectID(idParts[0], ObfuscatedObjectIDType.MEDIA_FILE);
		if(fileID<=0)
			return null;
		return getMediaFileRef(fileID, idParts[1], expectedPurpose, expectedOwnerID);
	}

	public static MediaFileRef getMediaFileRef(long fileID, String token, MediaFileUploadPurpose expectedPurpose, int expectedOwnerID){
		try{
			MediaFileRecord mfr=MediaStorage.getMediaFileRecord(fileID);
			if(mfr==null || !MediaFileUploadTokens.verifyToken(token, mfr.id(), expectedPurpose, expectedOwnerID))
				return null;
			MediaFileRef img=new MediaFileRef(fileID);
			img.fillIn(mfr);
			return img;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public static void deleteAbandonedFiles(){
		try{
			List<MediaFileRecord> fileRecords=MediaStorage.getUnreferencedMediaFileRecords();
			if(fileRecords.isEmpty()){
				LOG.trace("No files to delete");
				return;
			}
			LOG.trace("Deleting: {}", fileRecords);
			Set<MediaFileID> idsToDelete=fileRecords.stream().map(MediaFileRecord::id).collect(Collectors.toSet());
			try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
				DatabaseUtils.doWithTransaction(conn, ()->MediaStorage.deleteMediaFileRecords(idsToDelete.stream().map(MediaFileID::id).collect(Collectors.toSet())));
			}
			// Make sure that we only delete actual files on disk if the corresponding rows in the media_files table were successfully deleted.
			MediaFileStorageDriver.getInstance().deleteFiles(idsToDelete);
		}catch(SQLException x){
			LOG.warn("Failed to delete unused media files", x);
		}
	}

	/**
	 * Reads a file from a multipart request and runs it through {@link MediaUploadService}.
	 * Rejections become HTTP error responses with translated messages, through {@link Spark#halt}.
	 * @param fieldName the name of the form field with the file
	 */
	public static MediaFileRef saveUploadedMedia(Request req, Response resp, Account self, String fieldName, MediaFileUploadPurpose purpose, ProcessingOptions options){
		Lang l=lang(req);
		long maxSize=MediaUploadService.maxUploadSize(purpose);
		try{
			req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement(null, maxSize, -1L, 0));
			Part part;
			try{
				part=req.raw().getPart(fieldName);
			}catch(IllegalStateException x){
				// Payload Too Large
				Spark.halt(413, l.get("err_file_upload_too_large", Map.of("maxSize", l.formatFileSize(maxSize))));
				return null;
			}
			if(part==null)
				throw new BadRequestException();
			if(part.getSize()>maxSize){
				// Payload Too Large
				Spark.halt(413, l.get("err_file_upload_too_large", Map.of("maxSize", l.formatFileSize(maxSize))));
			}

			File temp=File.createTempFile("SmithereenUpload", null);
			try{
				try(FileOutputStream out=new FileOutputStream(temp)){
					Utils.copyBytes(part.getInputStream(), out);
				}
				return MediaUploadService.store(temp, part.getContentType(), self, purpose, options, Utils.context(req).getMediaProcessingQueue());
			}catch(MediaValidationException x){
				Map<String, Object> args=new HashMap<>(x.langArgs);
				if(args.get("maxSizeBytes") instanceof Long bytes)
					args.put("maxSize", l.formatFileSize(bytes));
				Spark.halt(x.httpStatus, l.get(x.langKey, args));
				throw new IllegalStateException();
			}finally{
				temp.delete();
			}
		}catch(IOException | ServletException | SQLException x){
			LOG.error("File upload failed", x);
			Spark.halt(500, l.get("err_file_upload"));
			throw new IllegalStateException();
		}
	}
}
