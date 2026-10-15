package smithereen.storage;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import smithereen.ApplicationContext;
import smithereen.Utils;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.LocalImage;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.lang.Lang;
import smithereen.libvips.VipsImage;
import smithereen.model.Account;
import smithereen.model.AttachmentHostContentObject;
import smithereen.model.Group;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.SizedImage;
import smithereen.model.User;
import smithereen.model.attachments.GraffitiAttachment;
import smithereen.model.media.ImageMetadata;
import smithereen.model.media.MediaFileID;
import smithereen.model.media.MediaFileMetadata;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.MediaFileUploadPurpose;
import smithereen.model.media.MediaFileUploadTokens;
import smithereen.model.photos.Photo;
import smithereen.storage.media.MediaFileStorageDriver;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.util.BlurHash;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.XTEA;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.utils.StringUtils;

import static smithereen.Utils.lang;

public class MediaStorageUtils{
	private static final Logger LOG=LoggerFactory.getLogger(MediaStorageUtils.class);
	public static final int QUALITY_LOSSLESS=-1;
	public static final long MAX_IMAGE_SIZE=10*1024*1024;
	/**
	 * Animated images are scaled down so that the total number of pixels in all frames stays within this budget,
	 * because encoding an animation takes time proportional to it.
	 */
	public static final long MAX_ANIMATION_PIXELS=50_000_000L;
	/**
	 * libvips metadata fields that describe an animation. They must survive until the image is written, otherwise it becomes a still.
	 */
	private static final Set<String> ANIMATION_METADATA_FIELDS=Set.of("page-height", "n-pages", "delay", "loop");

	/**
	 * Writes an image to a WebP file, scaling it down to fit the given bounds if needed.
	 * If {@code img} was loaded with all frames of an animation, the result is an animated WebP.
	 * @param outSize receives the width and height of the result (of one frame, for animations)
	 */
	public static long writeResizedWebpImage(VipsImage img, int widthOrSize, int height, int quality, File file, int[] outSize) throws IOException{
		int frameHeight=img.getFrameHeight();
		int frameCount=img.getLoadedFrames();
		boolean animated=frameCount>1;
		double factor;
		if(height==0){
			factor=(double) widthOrSize/(double) Math.max(img.getWidth(), frameHeight);
		}else{
			factor=Math.min((double)widthOrSize/(double)img.getWidth(), (double)height/(double)frameHeight);
		}
		if(animated){
			factor=Math.min(factor, 1.0);
			double totalPixels=(double)img.getWidth()*frameHeight*frameCount*factor*factor;
			if(totalPixels>MAX_ANIMATION_PIXELS)
				factor*=Math.sqrt(MAX_ANIMATION_PIXELS/totalPixels);
		}

		ArrayList<String> args=new ArrayList<>();
		if(quality==QUALITY_LOSSLESS){
			args.add("lossless=true");
		}else{
			args.add("Q="+quality);
		}

		boolean strip=!img.hasColorProfile();
		if(!strip || animated){
			for(String key:img.getFields()){
				if("icc-profile-data".equals(key) || (animated && ANIMATION_METADATA_FIELDS.contains(key)))
					continue;
				img.removeField(key);
			}
		}else{
			args.add("strip=true");
		}

		if(factor>=1.0){
			img.writeToFile(file.getAbsolutePath()+"["+String.join(",", args)+"]");
			outSize[0]=img.getWidth();
			outSize[1]=frameHeight;
		}else{
			VipsImage resized;
			int resizedFrameHeight;
			if(animated){
				// Resize the whole stack of frames at once, choosing the scales so that every frame ends up with the same integer height
				resizedFrameHeight=Math.max(1, (int)Math.round(frameHeight*factor));
				int resizedWidth=Math.max(1, (int)Math.round(img.getWidth()*factor));
				resized=img.resize(resizedWidth/(double)img.getWidth(), resizedFrameHeight/(double)frameHeight);
				if(resized.getHeight()!=resizedFrameHeight*frameCount){
					int actualHeight=resized.getHeight();
					resized.release();
					throw new IOException("Resized animation height "+actualHeight+" is not "+frameCount+" frames of "+resizedFrameHeight+" pixels");
				}
				resized.setFrameHeight(resizedFrameHeight);
			}else{
				resized=img.resize(factor);
				resizedFrameHeight=resized.getHeight();
			}
			try{
				resized.writeToFile(file.getAbsolutePath()+"["+String.join(",", args)+"]");
				outSize[0]=resized.getWidth();
				outSize[1]=resizedFrameHeight;
			}finally{
				resized.release();
			}
		}
		return file.length();
	}

	public static JsonObject serializeAttachment(ActivityPubObject att){
		if(att instanceof LocalImage li){
			JsonObjectBuilder jb=new JsonObjectBuilder()
					.add("type", "_LocalImage")
					.add("_fileID", li.fileID);
			if(li.photoID!=0)
				jb.add("_photoID", li.photoID);
			if(li.rotation!=null && li.rotation!=SizedImage.Rotation._0)
				jb.add("_rot", li.rotation.value());
			if(li.avaCropRects!=null)
				jb.add("_crop", Utils.gson.toJsonTree(li.avaCropRects));
			if(StringUtils.isNotEmpty(li.name))
				jb.add("name", li.name);
			return jb.build();
		}
		return att.asActivityPubObject(null);
	}

	public static void fillAttachmentObjects(ApplicationContext context, User self, List<ActivityPubObject> attachObjects, List<String> attachmentIDs, Map<String, String> altTexts, int attachmentCount, int maxAttachments, boolean allowGraffiti) throws SQLException{
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
				LocalImage img=new LocalImage();
				img.fileID=photo.localFileID;
				img.photoID=photoID;
				if(photo.metadata!=null)
					img.rotation=photo.metadata.rotation;
				MediaFileRecord mfr=MediaStorage.getMediaFileRecord(img.fileID);
				img.fillIn(mfr);
				attachObjects.add(img);
			}else{
				LocalImage img=getLocalImage(id, MediaFileUploadPurpose.ATTACHMENT, self.id);
				if(img==null || (img.isGraffiti && !allowGraffiti))
					continue;
				img.name=altTexts.get(id);
				attachObjects.add(img);
			}
			attachmentCount++;
			if(attachmentCount==maxAttachments)
				break;
		}
	}

	public static LocalImage getLocalImage(String id, MediaFileUploadPurpose expectedPurpose, int expectedOwnerID) throws SQLException{
		String[] idParts=id.split(":");
		if(idParts.length!=2)
			return null;
		long fileID=XTEA.decodeObjectID(idParts[0], ObfuscatedObjectIDType.MEDIA_FILE);
		if(fileID<=0)
			return null;
		return getLocalImage(fileID, idParts[1], expectedPurpose, expectedOwnerID);
	}

	public static LocalImage getLocalImage(long fileID, String token, MediaFileUploadPurpose expectedPurpose, int expectedOwnerID){
		try{
			MediaFileRecord mfr=MediaStorage.getMediaFileRecord(fileID);
			if(mfr==null || !MediaFileUploadTokens.verifyToken(token, mfr.id(), expectedPurpose, expectedOwnerID))
				return null;
			LocalImage img=new LocalImage();
			img.fileID=fileID;
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
	 * @param allowAnimation whether an animated GIF or WebP is kept animated. If false, uploading one fails with a 415 response.
	 */
	public static LocalImage saveUploadedImage(Request req, Response resp, Account self, boolean isGraffiti, String fieldName, boolean allowAnimation){
		return saveUploadedImage(req, resp, self, isGraffiti, fieldName, 2560, 0, allowAnimation, null);
	}

	public static LocalImage saveUploadedImage(Request req, Response resp, Account self, boolean isGraffiti, String fieldName, int maxWidthOrSize, int maxHeight, boolean allowAnimation, Consumer<VipsImage> extraValidator){
		Lang l=lang(req);
		try{
			req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement(null, MAX_IMAGE_SIZE, -1L, 0));
			Part part;
			try{
				part=req.raw().getPart(fieldName);
			}catch(IllegalStateException x){
				// Payload Too Large
				Spark.halt(413, l.get("err_file_upload_too_large", Map.of("maxSize", l.formatFileSize(MAX_IMAGE_SIZE))));
				return null;
			}
			if(part==null)
				throw new BadRequestException();
			if(part.getSize()>MAX_IMAGE_SIZE){
				// Payload Too Large
				Spark.halt(413, l.get("err_file_upload_too_large", Map.of("maxSize", l.formatFileSize(MAX_IMAGE_SIZE))));
			}

			String mime=part.getContentType();
			if(!mime.startsWith("image/")){
				// Unsupported Media Type
				Spark.halt(415, l.get("err_file_upload_image_format"));
			}

			File temp=File.createTempFile("SmithereenUpload", null);
			// The first frame. Used for validation and the blurhash, and written out as is for still images.
			VipsImage img=null;
			// All frames of an animation, or the same object as img for still images.
			VipsImage toWrite=null;
			try{
				try{
					try(FileOutputStream out=new FileOutputStream(temp)){
						Utils.copyBytes(part.getInputStream(), out);
					}
					img=new VipsImage(temp.getAbsolutePath());
				}catch(IOException x){
					LOG.warn("VipsImage error", x);
					Spark.halt(400, l.get("err_file_upload_image_format"));
					throw new IllegalStateException();
				}
				boolean animated=img.isAnimated();
				if(animated && !allowAnimation){
					// Unsupported Media Type
					Spark.halt(415, l.get("err_file_upload_animated_not_allowed"));
				}
				if(img.hasAlpha()){
					VipsImage flat=img.flatten(255, 255, 255);
					img.release();
					img=flat;
				}

				if(isGraffiti && (img.getWidth()!=GraffitiAttachment.WIDTH || img.getHeight()!=GraffitiAttachment.HEIGHT)){
					LOG.warn("Unexpected graffiti size {}x{}", img.getWidth(), img.getHeight());
					throw new BadRequestException();
				}

				if(extraValidator!=null)
					extraValidator.accept(img);

				if(animated){
					try{
						toWrite=new VipsImage(temp.getAbsolutePath(), true);
					}catch(IOException x){
						LOG.warn("VipsImage error", x);
						Spark.halt(400, l.get("err_file_upload_image_format"));
						throw new IllegalStateException();
					}
					if(toWrite.hasAlpha()){
						VipsImage flat=toWrite.flatten(255, 255, 255);
						toWrite.release();
						toWrite=flat;
					}
				}else{
					toWrite=img;
				}

				LocalImage photo=new LocalImage();
				File resizedFile=File.createTempFile("SmithereenUploadResized", ".webp");
				int[] outSize={0,0};
				writeResizedWebpImage(toWrite, maxWidthOrSize, maxHeight, isGraffiti ? MediaStorageUtils.QUALITY_LOSSLESS : 93, resizedFile, outSize);
				MediaFileMetadata meta=new ImageMetadata(outSize[0], outSize[1], BlurHash.encode(img, 4, 4), null, animated);
				MediaFileRecord fileRecord=MediaStorage.createMediaFileRecord(isGraffiti ? MediaFileType.IMAGE_GRAFFITI : MediaFileType.IMAGE_PHOTO, resizedFile.length(), self.user.id, meta);
				photo.fileID=fileRecord.id().id();
				photo.fillIn(fileRecord);
				MediaFileStorageDriver.getInstance().storeFile(resizedFile, fileRecord.id(), false);
				return photo;
			}finally{
				temp.delete();
				if(toWrite!=null && toWrite!=img)
					toWrite.release();
				if(img!=null)
					img.release();
			}
		}catch(IOException | ServletException | SQLException x){
			LOG.error("File upload failed", x);
			Spark.halt(500, l.get("err_file_upload"));
			throw new IllegalStateException();
		}
	}

	@NotNull
	public static String calculateBlurHash(@NotNull File file) throws IOException{
		VipsImage img=null;
		try{
			img=new VipsImage(file.getAbsolutePath());
			return BlurHash.encode(img, 4, 4);
		}finally{
			if(img!=null)
				img.release();
		}
	}

}
