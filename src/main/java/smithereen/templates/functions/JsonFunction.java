package smithereen.templates.functions;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import smithereen.Utils;

public class JsonFunction implements Function{

	@Override
	public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber){
		// The output is often used with |raw inside <script> blocks, so make sure strings can't close the script tag
		// or start an HTML comment. These escapes are still valid JSON and decode to the same values.
		return Utils.gson.toJson(args.get("obj"))
				.replace("<", "\\u003c")
				.replace(">", "\\u003e")
				.replace("&", "\\u0026")
				.replace("\u2028", "\\u2028")
				.replace("\u2029", "\\u2029");
	}

	@Override
	public List<String> getArgumentNames(){
		return Collections.singletonList("obj");
	}
}
