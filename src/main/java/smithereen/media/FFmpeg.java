package smithereen.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import smithereen.Config;

/**
 * Runs the ffmpeg and ffprobe command-line tools on uploaded files. Every call runs with a timeout, reads nothing from
 * stdin, and only allows the "file" protocol, so a crafted file cannot make ffmpeg open anything else.
 */
public final class FFmpeg{
	private static final Logger LOG=LoggerFactory.getLogger(FFmpeg.class);
	private static final long PROBE_TIMEOUT_SECONDS=60;
	private static final long POSTER_TIMEOUT_SECONDS=120;
	private static final int MAX_STDERR_LENGTH=4000;
	private static volatile Boolean available;

	private FFmpeg(){}

	/**
	 * A video file as ffprobe sees it.
	 * @param width width after applying the rotation stored in the file
	 * @param duration in seconds
	 * @param frameRate frames per second, 0 if unknown
	 */
	public record ProbeResult(int width, int height, double duration, double frameRate, boolean hasAudio, String videoCodec, String formatName){
		/**
		 * @return whether ffprobe read this as an image file (GIF, or any still through the image2 demuxers) rather than a video
		 */
		public boolean isImageFormat(){
			return formatName!=null && (formatName.contains("image2") || formatName.contains("_pipe") || formatName.equals("gif"));
		}
	}

	/**
	 * @return whether ffmpeg and ffprobe can be run. Checked once; a server without them refuses video uploads instead of failing.
	 */
	public static boolean isAvailable(){
		Boolean a=available;
		if(a==null){
			synchronized(FFmpeg.class){
				a=available;
				if(a==null){
					a=checkAvailable();
					available=a;
				}
			}
		}
		return a;
	}

	private static boolean checkAvailable(){
		try{
			String ffmpegVersion=run(List.of(Config.ffmpegPath, "-version"), 30);
			String ffprobeVersion=run(List.of(Config.ffprobePath, "-version"), 30);
			LOG.info("ffmpeg found: {}", ffmpegVersion.lines().findFirst().orElse("?"));
			LOG.debug("ffprobe found: {}", ffprobeVersion.lines().findFirst().orElse("?"));
			return true;
		}catch(IOException x){
			LOG.warn("ffmpeg is not available, video uploads are disabled: {}", x.getMessage());
			return false;
		}
	}

	/**
	 * Reads the properties of a video file.
	 * @throws MediaValidationException if the file has no video stream or can't be read
	 */
	public static ProbeResult probe(File file) throws IOException, MediaValidationException{
		String json;
		try{
			json=run(List.of(Config.ffprobePath, "-v", "error", "-protocol_whitelist", "file", "-probesize", "50M", "-analyzeduration", "20M",
					"-print_format", "json", "-show_format", "-show_streams", file.getAbsolutePath()), PROBE_TIMEOUT_SECONDS);
		}catch(IOException x){
			LOG.debug("ffprobe failed: {}", x.getMessage());
			throw MediaValidationException.unreadable();
		}
		JsonObject root;
		try{
			root=JsonParser.parseString(json).getAsJsonObject();
		}catch(RuntimeException x){
			throw MediaValidationException.unreadable();
		}
		JsonObject video=null;
		boolean hasAudio=false;
		JsonArray streams=root.has("streams") ? root.getAsJsonArray("streams") : new JsonArray();
		for(JsonElement el:streams){
			JsonObject stream=el.getAsJsonObject();
			String type=optString(stream, "codec_type");
			if("video".equals(type) && video==null && !isAttachedPicture(stream))
				video=stream;
			else if("audio".equals(type))
				hasAudio=true;
		}
		if(video==null)
			throw MediaValidationException.unreadable();
		int width=optInt(video, "width"), height=optInt(video, "height");
		if(width<=0 || height<=0)
			throw MediaValidationException.unreadable();
		int rotation=rotation(video);
		if(rotation==90 || rotation==270){
			int t=width;
			width=height;
			height=t;
		}
		double duration=0;
		if(root.has("format") && root.getAsJsonObject("format").has("duration"))
			duration=parseDouble(optString(root.getAsJsonObject("format"), "duration"));
		if(duration<=0)
			duration=parseDouble(optString(video, "duration"));
		if(duration<=0)
			throw MediaValidationException.unreadable();
		double frameRate=parseFraction(optString(video, "avg_frame_rate"));
		if(frameRate<=0)
			frameRate=parseFraction(optString(video, "r_frame_rate"));
		String formatName=root.has("format") ? optString(root.getAsJsonObject("format"), "format_name") : null;
		return new ProbeResult(width, height, duration, frameRate, hasAudio, optString(video, "codec_name"), formatName);
	}

	/**
	 * Writes one frame of the video as a PNG, scaled to the given size.
	 */
	public static void extractPoster(File input, double atSeconds, int width, int height, File output) throws IOException{
		ArrayList<String> cmd=baseCommand();
		cmd.addAll(List.of("-ss", String.format(Locale.US, "%.3f", atSeconds), "-i", input.getAbsolutePath(),
				"-map", "0:v:0", "-frames:v", "1", "-vf", "scale="+width+":"+height, "-f", "image2", "-update", "1", output.getAbsolutePath()));
		run(cmd, POSTER_TIMEOUT_SECONDS);
	}

	/**
	 * Transcodes to H.264 and AAC in an MP4 with the index up front, scaled to the given size, with the audio capped at stereo 128 kbit/s
	 * and the frame rate at 30. Uses the number of threads from the configuration so that one job can't take over the server.
	 */
	public static void transcode(File input, File output, int width, int height, double sourceFrameRate) throws IOException{
		int threads=Math.max(1, Config.ffmpegThreads);
		String filter="scale="+width+":"+height;
		if(sourceFrameRate>30.5)
			filter+=",fps=30";
		ArrayList<String> cmd=baseCommand();
		cmd.addAll(List.of("-threads", String.valueOf(threads), "-filter_threads", String.valueOf(threads), "-i", input.getAbsolutePath(),
				"-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
				"-vf", filter,
				"-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-profile:v", "high", "-level", "4.0", "-pix_fmt", "yuv420p",
				"-x264-params", "threads="+threads,
				"-c:a", "aac", "-b:a", "128k", "-ac", "2",
				"-movflags", "+faststart", "-max_muxing_queue_size", "1024",
				"-t", String.valueOf(Config.videoMaxDuration),
				output.getAbsolutePath()));
		run(cmd, Config.videoTranscodeTimeout);
	}

	private static ArrayList<String> baseCommand(){
		ArrayList<String> cmd=new ArrayList<>();
		File nice=new File("/usr/bin/nice");
		if(nice.canExecute()){
			cmd.add(nice.getAbsolutePath());
			cmd.add("-n");
			cmd.add("19");
		}
		cmd.addAll(List.of(Config.ffmpegPath, "-nostdin", "-y", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file"));
		return cmd;
	}

	/**
	 * Runs a command, waits for it with a timeout, and returns its standard output.
	 * @throws IOException if the command can't be started, times out, or exits with an error; the message carries the tail of stderr
	 */
	static String run(List<String> command, long timeoutSeconds) throws IOException{
		LOG.trace("Running {}", command);
		ProcessBuilder pb=new ProcessBuilder(command);
		pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
		Process process=pb.start();
		// Both pipes are drained on their own threads so that output can't block the process, and so that the
		// wait below is a real timeout: a process that hangs is killed rather than blocking the caller on a read.
		StringBuilder stderr=new StringBuilder();
		ByteArrayOutputStream stdoutBytes=new ByteArrayOutputStream();
		Thread stderrReader=Thread.ofVirtual().start(()->{
			try(InputStream in=process.getErrorStream()){
				byte[] buf=new byte[4096];
				int read;
				while((read=in.read(buf))>0){
					if(stderr.length()<MAX_STDERR_LENGTH)
						stderr.append(new String(buf, 0, read, StandardCharsets.UTF_8));
				}
			}catch(IOException ignore){}
		});
		Thread stdoutReader=Thread.ofVirtual().start(()->{
			try(InputStream in=process.getInputStream()){
				in.transferTo(stdoutBytes);
			}catch(IOException ignore){}
		});
		boolean exited;
		try{
			exited=process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		}catch(InterruptedException x){
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for "+command.getFirst());
		}
		if(!exited){
			process.destroyForcibly();
			throw new IOException(command.getFirst()+" did not finish within "+timeoutSeconds+" seconds");
		}
		try{
			stdoutReader.join(5000);
			stderrReader.join(5000);
		}catch(InterruptedException ignore){
			Thread.currentThread().interrupt();
		}
		if(process.exitValue()!=0)
			throw new IOException(command.getFirst()+" exited with code "+process.exitValue()+": "+stderr.toString().strip());
		return stdoutBytes.toString(StandardCharsets.UTF_8);
	}

	private static boolean isAttachedPicture(JsonObject stream){
		// Cover art in audio files shows up as a video stream with this disposition
		if(stream.has("disposition") && stream.get("disposition").isJsonObject()){
			JsonObject disposition=stream.getAsJsonObject("disposition");
			return optInt(disposition, "attached_pic")==1;
		}
		return false;
	}

	private static int rotation(JsonObject stream){
		int rotation=0;
		if(stream.has("side_data_list") && stream.get("side_data_list").isJsonArray()){
			for(JsonElement el:stream.getAsJsonArray("side_data_list")){
				if(el.isJsonObject() && el.getAsJsonObject().has("rotation")){
					rotation=(int) Math.round(parseDouble(optString(el.getAsJsonObject(), "rotation")));
					break;
				}
			}
		}
		if(rotation==0 && stream.has("tags") && stream.get("tags").isJsonObject())
			rotation=(int) Math.round(parseDouble(optString(stream.getAsJsonObject("tags"), "rotate")));
		rotation=((rotation%360)+360)%360;
		return rotation;
	}

	private static String optString(JsonObject obj, String key){
		JsonElement el=obj.get(key);
		return el!=null && el.isJsonPrimitive() ? el.getAsString() : null;
	}

	private static int optInt(JsonObject obj, String key){
		JsonElement el=obj.get(key);
		try{
			return el!=null && el.isJsonPrimitive() ? el.getAsInt() : 0;
		}catch(NumberFormatException x){
			return 0;
		}
	}

	private static double parseDouble(String s){
		if(s==null)
			return 0;
		try{
			return Double.parseDouble(s);
		}catch(NumberFormatException x){
			return 0;
		}
	}

	private static double parseFraction(String s){
		if(s==null)
			return 0;
		int slash=s.indexOf('/');
		if(slash==-1)
			return parseDouble(s);
		double num=parseDouble(s.substring(0, slash)), den=parseDouble(s.substring(slash+1));
		return den==0 ? 0 : num/den;
	}
}
