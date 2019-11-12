package cn.how2j.hot;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReloadableApplication {
	
	public static void setUp(String startClassName) {
		
		
		ReloadableClassLoader loader = new ReloadableClassLoader(startClassName);

		try {
			loader.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	public static void setUp(Class<?> clazz) {
		setUp(clazz.getName());
	}
	
	private static class ReloadableClassLoader extends  URLClassLoader implements Runnable{

		private String startClass;
		private Map<String, Long> fileUpdateTimeMap = new HashMap<>();
		
		private Object startInstance;
		
		private boolean scanThreadContinue =true;

		public ReloadableClassLoader(Class<?> clazz) {
			this(clazz.getName());
		}
		
		public ReloadableClassLoader(String startClass) {
			super(new URL[] {},null);
			this.startClass = startClass;
			try {
				init();
				Thread t =new Thread(this);
				t.start();
				
				begin();
				
			} catch (ClassCastException e) {
				scanThreadContinue  =false;
				System.err.println(e.getMessage());
				System.exit(1);
			}
			catch(Exception e) {
				scanThreadContinue =false;
				e.printStackTrace();
			}


		}
		
	    private void begin()  throws Exception{
				Class<?> startClazz = Class.forName(startClass,true,this);
				Class<?> lifeCycleClazz = Class.forName("cn.how2j.hot.LifeCycle",true,this);

				Class<?> interfaces[]  = startClazz.getInterfaces();
				boolean isSubOfLifeCycle = false;
				for (Class<?> interfase : interfaces) {
					if(interfase == lifeCycleClazz) {
						isSubOfLifeCycle = true;
						break;
					}
				}
				
				if(!isSubOfLifeCycle) {
					
					throw new ClassCastException(startClazz.getName() +" 未实现  cn.how2j.hot.LifeCycle 接口，无法启动");
				}
				
				
				
				Constructor<?> constructor = startClazz.getConstructor();
				startInstance =constructor.newInstance();


				System.out.println("invoke the start() of class: " +startClass);
				startClazz.getMethod("start").invoke(startInstance);
				
				
				
		}
	    
		private void restart() {
			System.out.println("restarting " + startClass);
			ReloadableClassLoader reloader = new ReloadableClassLoader(startClass);
			try {
				reloader.close();
			} catch (IOException e) {
				e.printStackTrace();
			};
	    }
	    
	    
	    private void end() {
			try {
				scanThreadContinue = false;
				Class<?> clazz = Class.forName(startClass,true,this);
				clazz.getMethod("stop").invoke(startInstance);
				System.out.println("invoke the stop() of class: " +startClass);
			} catch (Exception e) {
				e.printStackTrace();
			}
	    }

		private void init() throws ClassNotFoundException, IOException {
			String javaClassPath = System.getProperty("java.class.path");
			List<String> classesFolders = new ArrayList<>();
			String[] paths= javaClassPath.split(";");
			
			for (String path : paths) {
				if(path.endsWith("jar")) {
					File file= new File(path);
					fileUpdateTimeMap.put(path, file.lastModified());
					super.addURL(file.toURI().toURL());
				}
				else {
					classesFolders.add(path);
				}
			}
			
			for (String classesFolder : classesFolders) {
				List<File> files = listFiles(classesFolder);
				for (File file : files) {
					if(file.getName().endsWith(".class")) {
						String fileName = file.getAbsolutePath().replace(classesFolder+"\\", "");
						fileName = fileName.replace(classesFolder+"/", "");
						
						String fullClassName = fileName.replaceAll("\\\\", ".");
						fullClassName = fullClassName.replaceAll("/", ".");

						fullClassName = fullClassName.substring( 0, fullClassName.length()-6);
						findClass(fullClassName);
						fileUpdateTimeMap.put(file.getAbsolutePath(), file.lastModified());
						
					}
				}
			}		
	    	
		}


	    protected Class<?> findClass(String fullClassName) throws ClassNotFoundException {
	        Class<?> clazz = null;
	        try {
				clazz = super.findClass(fullClassName); //这表示通过 URLClassLoader 从 jar 里找
			} catch (ClassNotFoundException e1) {
				//找不到说明jar里没有，就要到 classpath里去找了
			}
	        if(null!=clazz)
	        	return clazz;

	        try {
	            byte[] data = getClassFileBytes(fullClassName);
	            clazz = defineClass(fullClassName, data, 0, data.length);
	        } catch (Exception e) {
	            e.printStackTrace();
	        }
	        return clazz;
	 
	    }


		private byte[] getClassFileBytes(String fullClassName) throws IOException {
			byte result[] = null;
			String javaClassPath = System.getProperty("java.class.path");
			List<String> classesFolders = new ArrayList<>();
			String[] paths= javaClassPath.split(";");
			
			for (String path : paths) {
				if(!path.endsWith("jar")) 				
					classesFolders.add(path);
			}

			String fileName = fullClassName.replaceAll("\\.", "/")+".class";
			

			for (String classesFolder : classesFolders) {
				
				File classFile = new File(classesFolder,fileName);
				
				if(classFile.exists()) {
					
					result= readBytes(classFile);
					break;
				}
				
			}
			return result;
		}


		@Override
		public void run() {

			repeatable_while: 
			while(scanThreadContinue) {
				String javaClassPath = System.getProperty("java.class.path");
				List<String> classesFolders = new ArrayList<>();
				String[] paths= javaClassPath.split(";");
				
				for (String path : paths) {
					if(path.endsWith("jar")) 		{		
						
						
						Long recordedLastModified = fileUpdateTimeMap.get(new File(path).getAbsolutePath());
						Long currentLastModified = new File(path).lastModified();
						
						if(null==recordedLastModified) {
							end();
							restart();
							break repeatable_while;
						}
						
						if(currentLastModified>recordedLastModified) {
							end();
							restart();

							break repeatable_while;
						}
					}
					else {
						classesFolders.add(path);
					}
				}
				
				
				for (String classesFolder : classesFolders) {
					List<File> files = listFiles(classesFolder);
					for (File file : files) {
						if(file.getName().endsWith(".class")) {
							Long recordedLastModified = fileUpdateTimeMap.get(file.getAbsolutePath());
							Long currentLastModified = file.lastModified();
							if(null==recordedLastModified) {
								end();
								restart();
								break repeatable_while;
							}
							
							if(currentLastModified>recordedLastModified) {
								end();
								restart();

								break repeatable_while;
							}
						}
					}
				}				
				
				
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				
			}
			
		}
		
		public static List<File> listFiles(String fileName){
			return listFiles(new File(fileName));
		}

		public static List<File> listFiles(File file){
			List<File> result = new ArrayList<>();
			
	        if(file.isFile())
	        	result.add(file);
	        
	          
	        if(file.isDirectory()){
	            File[] fs = file.listFiles();
	            if(null!=fs)
	            for (File f : fs) {
	                List<File> files=  listFiles(f);
	                result.addAll(files);
	            }
	        }
	        
	        return result;
	    }
		
		
		
		public byte[] readBytes(String fileName) throws IOException {
			return readBytes(new File(fileName));
		}
			
		
		public byte[] readBytes(File file) throws IOException {
			
			byte[] bytes = new byte[(int) file.length()];
			
			FileInputStream fis = new FileInputStream(file);
			fis.read(bytes);
			
			fis.close();
			
			return bytes;
			
			
			
			
		}
	}
}
