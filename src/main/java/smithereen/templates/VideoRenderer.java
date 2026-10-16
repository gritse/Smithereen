package smithereen.templates;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import smithereen.lang.Lang;
import smithereen.model.SizedImage;
import smithereen.model.media.Video;
import smithereen.text.TextProcessor;
import smithereen.util.BlurHash;
import spark.utils.StringUtils;

/**
 * The HTML of a video attachment: a player once the video is ready, the poster with a notice while it is being
 * transcoded or when transcoding failed. The same markup is sent over the websocket when the status changes, and
 * the client swaps it in by the file ID.
 */
public final class VideoRenderer{
	private VideoRenderer(){}

	public static String render(Video video, String altText, Lang lang){
		String fileID=video.record().id().getEncodedID();
		SizedImage poster=video.getPoster();
		StringBuilder sb=new StringBuilder("<div class=\"videoAttachment");
		switch(video.getStatus()){
			case PROCESSING -> sb.append(" mediaProcessing videoProcessing");
			case FAILED -> sb.append(" videoFailed");
			case READY -> {}
		}
		sb.append("\" data-media-file-id=\"").append(fileID).append('"');
		if(StringUtils.isNotEmpty(altText))
			sb.append(" data-alt=\"").append(TextProcessor.escapeHTML(altText)).append('"');
		String blurHash=video.getBlurHash();
		if(StringUtils.isNotEmpty(blurHash))
			sb.append(String.format(Locale.US, " style=\"background-color: #%06X\"", BlurHash.decodeToSingleColor(blurHash)));
		sb.append('>');
		switch(video.getStatus()){
			case READY -> {
				sb.append("<video controls preload=\"metadata\" playsinline width=\"").append(video.getWidth()).append("\" height=\"").append(video.getHeight()).append('"');
				if(poster!=null)
					sb.append(" poster=\"").append(poster.getUriForSizeAndFormat(SizedImage.Type.PHOTO_MEDIUM, SizedImage.Format.JPEG)).append('"');
				if(StringUtils.isNotEmpty(altText))
					sb.append(" aria-label=\"").append(TextProcessor.escapeHTML(altText)).append('"');
				sb.append("><source src=\"").append(Objects.toString(video.getFileURL())).append("\" type=\"video/mp4\"/></video>");
			}
			case PROCESSING, FAILED -> {
				if(poster!=null)
					sb.append(poster.generateHTML(SizedImage.Type.PHOTO_MEDIUM, List.of("videoPoster"), null, 0, 0, true, altText));
				sb.append("<div class=\"videoOverlay\">");
				if(video.getStatus()==smithereen.model.media.MediaFileStatus.PROCESSING)
					sb.append("<span class=\"loader white\"></span>");
				sb.append("<span class=\"videoOverlayText\">")
						.append(TextProcessor.escapeHTML(lang.get(video.getStatus()==smithereen.model.media.MediaFileStatus.PROCESSING ? "video_processing" : "video_processing_failed")))
						.append("</span></div>");
			}
		}
		sb.append("</div>");
		return sb.toString();
	}
}
