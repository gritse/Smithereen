package smithereen;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import io.pebbletemplates.pebble.loader.DelegatingLoader;
import io.pebbletemplates.pebble.loader.Loader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import smithereen.templates.SmithereenExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parses every template with the same engine configuration the server uses, so that a syntax error
 * (an unbalanced tag, a typo in a block name) fails the build instead of a page in production.
 */
public class TemplateSyntaxTest{
	private static final Path TEMPLATES=Paths.get("src/main/resources/templates");

	private static PebbleEngine makeEngine(String... dirs){
		List<Loader<?>> loaders=new ArrayList<>();
		for(String dir:dirs){
			ClasspathLoader loader=new ClasspathLoader();
			loader.setPrefix("templates/"+dir);
			loader.setSuffix(".twig");
			loaders.add(loader);
		}
		return new PebbleEngine.Builder()
				.loader(new DelegatingLoader(loaders))
				.extension(new SmithereenExtension())
				.strictVariables(false)
				.build();
	}

	@Test
	public void allTemplatesParse() throws IOException{
		Map<String, PebbleEngine> engines=Map.of(
				"desktop", makeEngine("desktop", "common"),
				"mobile", makeEngine("mobile", "common"),
				"popup", makeEngine("popup"),
				"email", makeEngine("email")
		);
		List<String> failures=new ArrayList<>();
		int checked=0;
		for(String dir:List.of("desktop", "mobile", "common", "popup", "email")){
			List<Path> files;
			try(Stream<Path> stream=Files.walk(TEMPLATES.resolve(dir))){
				files=stream.filter(p->p.toString().endsWith(".twig")).toList();
			}
			List<String> engineNames=dir.equals("common") ? List.of("desktop", "mobile") : List.of(dir);
			for(Path file:files){
				String name=TEMPLATES.resolve(dir).relativize(file).toString().replaceAll("\\.twig$", "");
				for(String engineName:engineNames){
					checked++;
					try{
						engines.get(engineName).getTemplate(name);
					}catch(Exception x){
						failures.add(dir+"/"+name+" ["+engineName+"]: "+x.getMessage());
					}
				}
			}
		}
		assertTrue(checked>300, "Expected to find the templates under "+TEMPLATES.toAbsolutePath());
		assertTrue(failures.isEmpty(), ()->String.join("\n", failures));
	}
}
