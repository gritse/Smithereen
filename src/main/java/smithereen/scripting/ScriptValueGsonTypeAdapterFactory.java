package smithereen.scripting;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ScriptValueGsonTypeAdapterFactory implements TypeAdapterFactory{
	@Override
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type){
		if(ScriptValue.class.isAssignableFrom(type.getRawType())){
			return (TypeAdapter<T>) new Adapter(gson);
		}
		return null;
	}

	private static final ThreadLocal<int[]> depth=ThreadLocal.withInitial(()->new int[1]);

	private class Adapter extends TypeAdapter<ScriptValue>{
		private final Gson gson;

		private Adapter(Gson gson){
			this.gson=gson;
		}

		@Override
		public void write(JsonWriter out, ScriptValue value) throws IOException{
			switch(value){
				case ScriptValue.Str(String s) -> out.value(s);
				case ScriptValue.Num(double n) -> {
					if(n%1==0)
						out.value((long)n);
					else
						out.value(n);
				}
				case ScriptValue.Bool(boolean b) -> out.value(b);
				// Scripts can build cyclic or very deep structures (e.g. a.push(a)); bound the recursion
				case ScriptValue.Obj(Map<String, ScriptValue> o) -> writeNested(()->gson.getAdapter(Map.class).write(out, o));
				case ScriptValue.Arr(List<ScriptValue> a) -> writeNested(()->gson.getAdapter(List.class).write(out, a));
				case null -> out.nullValue();
			}
		}

		private void writeNested(NestedWriter w) throws IOException{
			int[] d=depth.get();
			if(d[0]>=ScriptVM.MAX_NESTING_DEPTH)
				throw new ScriptRuntimeException("Value is nested too deeply or contains a cycle", 0);
			d[0]++;
			try{
				w.write();
			}finally{
				d[0]--;
			}
		}

		@Override
		public ScriptValue read(JsonReader in) throws IOException{
			return null;
		}
	}

	@FunctionalInterface
	private interface NestedWriter{
		void write() throws IOException;
	}
}
