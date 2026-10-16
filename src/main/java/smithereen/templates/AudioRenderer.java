package smithereen.templates;

import java.util.Locale;
import java.util.Objects;

import smithereen.lang.Lang;
import smithereen.model.media.Audio;
import smithereen.model.media.MediaFileStatus;
import smithereen.text.TextProcessor;
import spark.utils.StringUtils;

/**
 * The HTML of an audio attachment: a player once the file is ready, its title with a notice before that.
 * Like {@link VideoRenderer}, the same markup is sent when the status changes and swapped in by the file ID.
 */
public final class AudioRenderer{
	private AudioRenderer(){}

	public static String render(Audio audio, String altText, Lang lang){
		String fileID=audio.record().id().getEncodedID();
		StringBuilder sb=new StringBuilder("<div class=\"audioAttachment");
		switch(audio.getStatus()){
			case PROCESSING -> sb.append(" mediaProcessing audioProcessing");
			case FAILED -> sb.append(" audioFailed");
			case READY -> {}
		}
		sb.append("\" data-media-file-id=\"").append(fileID).append('"');
		if(StringUtils.isNotEmpty(altText))
			sb.append(" data-alt=\"").append(TextProcessor.escapeHTML(altText)).append('"');
		sb.append('>');

		String title=StringUtils.isNotEmpty(audio.getTitle()) ? audio.getTitle() : (StringUtils.isNotEmpty(altText) ? altText : lang.get("content_type_audio"));
		sb.append("<div class=\"audioInfo\"><span class=\"audioTitle\">").append(TextProcessor.escapeHTML(title)).append("</span>");
		if(StringUtils.isNotEmpty(audio.getArtist()))
			sb.append("<span class=\"audioArtist\">").append(TextProcessor.escapeHTML(audio.getArtist())).append("</span>");
		sb.append("<span class=\"audioDuration\">").append(formatDuration(audio.getDuration())).append("</span></div>");
		switch(audio.getStatus()){
			case READY -> sb.append("<audio controls preload=\"none\" src=\"").append(Objects.toString(audio.getFileURL())).append("\"></audio>");
			case PROCESSING -> sb.append("<div class=\"audioNotice\"><span class=\"loader\"></span><span>").append(TextProcessor.escapeHTML(lang.get("audio_processing"))).append("</span></div>");
			case FAILED -> sb.append("<div class=\"audioNotice\">").append(TextProcessor.escapeHTML(lang.get("audio_processing_failed"))).append("</div>");
		}
		sb.append("</div>");
		return sb.toString();
	}

	public static String formatDuration(int seconds){
		if(seconds>=3600)
			return String.format(Locale.US, "%d:%02d:%02d", seconds/3600, seconds%3600/60, seconds%60);
		return String.format(Locale.US, "%d:%02d", seconds/60, seconds%60);
	}
}
