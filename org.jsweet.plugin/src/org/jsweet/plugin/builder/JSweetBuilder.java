/*
 * Copyright 2015 CINCHEO SAS <renaud.pawlak@cincheo.fr>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jsweet.plugin.builder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.jsweet.JSweetConfig;
import org.jsweet.plugin.Log;
import org.jsweet.plugin.preferences.Preferences;
import org.jsweet.transpiler.JSweetFactory;
import org.jsweet.transpiler.JSweetProblem;
import org.jsweet.transpiler.JSweetTranspiler;
import org.jsweet.transpiler.ModuleKind;
import org.jsweet.transpiler.Severity;
import org.jsweet.transpiler.SourceFile;
import org.jsweet.transpiler.SourcePosition;
import org.jsweet.transpiler.TranspilationHandler;
import org.jsweet.transpiler.candy.CandyProcessor;
import org.jsweet.transpiler.util.Util;

public class JSweetBuilder extends IncrementalProjectBuilder {

	public static final String ID = "org.jsweet.plugin.jsweetBuilder";
	public static final String JSWEET_PROBLEM_MARKER_TYPE = "org.jsweet.plugin.jsweetProblem";

	static class BuildingContext {
		public final String profile;
		public final IProject project;
		// watch mode does not work (yet?) under Windows, so we do not use it
		public boolean USE_WATCH_MODE = false;
		public final Map<File, SourceFile> sourceFiles = new HashMap<>();
		public JSweetTranspiler transpiler;

		public BuildingContext(IProject project, String profile) {
			this.project = project;
			this.profile = profile;
		}
	}

	private static boolean hasFile(File folder) {
		if (folder.isDirectory()) {
			File[] files = folder.listFiles();
			if (files != null) {
				for (File file : folder.listFiles()) {
					if (file.isFile()) {
						return true;
					} else {
						return hasFile(file);
					}
				}
			}
		}
		return false;
	}

	private static void cleanFiles(BuildingContext context) throws CoreException {
		try {
			context.project.deleteMarkers(JSWEET_PROBLEM_MARKER_TYPE, true, IResource.DEPTH_INFINITE);
			File tsOutDir = new File(context.project.getLocation().toFile(),
					Preferences.getTsOutputFolder(context.project, context.profile));
			LinkedList<File> files = new LinkedList<>();
			if (tsOutDir.exists()) {
				Util.addFiles(".ts", tsOutDir, files);
				for (File f : files) {
					FileUtils.deleteQuietly(f);
				}
				if (!hasFile(tsOutDir)) {
					FileUtils.deleteQuietly(tsOutDir);
				}
			}
			files.clear();
			File jsOutDir = new File(context.project.getLocation().toFile(),
					Preferences.getJsOutputFolder(context.project, context.profile));
			if (jsOutDir.exists()) {
				Util.addFiles(".js", jsOutDir, files);
				Util.addFiles(".js.map", jsOutDir, files);
				for (File f : files) {
					FileUtils.deleteQuietly(f);
				}
				if (!hasFile(jsOutDir)) {
					FileUtils.deleteQuietly(jsOutDir);
				}
			}
			context.project.refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch (NoClassDefFoundError e) {
			e.printStackTrace();
		}
	}

	private static void autoFillClassPath(IProject project) throws CoreException {
		if (project.isNatureEnabled("org.eclipse.jdt.core.javanature")) {
			IJavaProject javaProject = JavaCore.create(project);
			IClasspathEntry[] cp = javaProject.getRawClasspath();
			File processed = project.getLocation().append(JSweetTranspiler.TMP_WORKING_DIR_NAME + File.separator
					+ CandyProcessor.CANDIES_DIR_NAME).toFile();
			IClasspathEntry e = javaProject
					.decodeClasspathEntry("<classpathentry kind=\"lib\" path=\"" + JSweetTranspiler.TMP_WORKING_DIR_NAME
							+ File.separator + CandyProcessor.CANDIES_DIR_NAME + "\" sourcepath=\""
							+ JSweetTranspiler.TMP_WORKING_DIR_NAME + File.separator
							+ CandyProcessor.CANDIES_SOURCES_DIR_NAME + "\" />");
			if (project.isNatureEnabled(JSweetNature.ID)) {
				if (!processed.exists()) {
					processed.mkdirs();
					project.refreshLocal(IResource.DEPTH_INFINITE, null);
				}
				if (!ArrayUtils.contains(cp, e)) {
					Log.info("adding " + e + " to build path");
					cp = ArrayUtils.add(cp, 0, e);
					javaProject.setRawClasspath(cp, true, null);
				}
			} else {
				if (ArrayUtils.contains(cp, e)) {
					Log.info("removing " + e + " from build path");
					cp = ArrayUtils.remove(cp, ArrayUtils.indexOf(cp, e));
					javaProject.setRawClasspath(cp, true, null);
				}
			}
		}
	}

	public static void clean(IProject project, IProgressMonitor monitor) throws CoreException {
		// adds processed class directory to the build path to ensure that
		// mixins are available
		File tmp = project.getLocation().append(JSweetTranspiler.TMP_WORKING_DIR_NAME).toFile();
		FileUtils.deleteQuietly(tmp);
		autoFillClassPath(project);
		// delete markers set and files created
		for (String profile : Preferences.parseProfiles(project)) {
			cleanFiles(new BuildingContext(project, profile));
		}
	}

	/**
	 * Forces a full clean/rebuild of all the workspace projects that have the
	 * JSweet nature.
	 */
	public static void rebuildWorkspace() {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		rebuildProjects(Arrays.asList(workspace.getRoot().getProjects()));
	}

	/**
	 * Forces a full clean of all the workspace projects that have the JSweet
	 * nature.
	 */
	public static void cleanWorkspace() {
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		cleanProjects(Arrays.asList(workspace.getRoot().getProjects()));
	}

	/**
	 * Forces a full clean rebuild of the given JSweet project.
	 */
	public static void rebuildProject(IProject project) {
		rebuildProjects(Arrays.asList(project));
	}

	/**
	 * Forces a clean of the given JSweet project.
	 */
	public static void cleanProject(IProject project) {
		rebuildProjects(Arrays.asList(project));
	}

	private static void rebuildProjects(final List<IProject> projects) {
		String name = "rebuild";// Resources.BUNDLE.getString("preferences.compiler.rebuild.job.name");
		Job job = new Job(name) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					for (IProject project : projects) {
						if (project.isOpen() && project.hasNature(JSweetNature.ID)) {
							project.build(IncrementalProjectBuilder.CLEAN_BUILD, JSweetBuilder.ID, null, monitor);
							project.build(IncrementalProjectBuilder.FULL_BUILD, JSweetBuilder.ID, null, monitor);
						}
					}
					return Status.OK_STATUS;
				} catch (CoreException e) {
					return e.getStatus();
				}
			}
		};
		job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
		job.schedule();
	}

	private static void cleanProjects(final List<IProject> projects) {
		String name = "clean";// Resources.BUNDLE.getString("preferences.compiler.rebuild.job.name");
		Job job = new Job(name) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					for (IProject project : projects) {
						if (project.isOpen() && project.hasNature(JSweetNature.ID)) {
							project.build(IncrementalProjectBuilder.CLEAN_BUILD, JSweetBuilder.ID, null, monitor);
						}
					}
					return Status.OK_STATUS;
				} catch (CoreException e) {
					return e.getStatus();
				}
			}
		};
		job.setRule(ResourcesPlugin.getWorkspace().getRuleFactory().buildRule());
		job.schedule();
	}

	class IncrementalGrabJavaFileVisitor implements IResourceDeltaVisitor {
		private IJavaProject project;
		private BuildingContext context;
		public List<File> javaFiles = new ArrayList<File>();
		public List<IFile> javaResourceFiles = new ArrayList<IFile>();

		public IncrementalGrabJavaFileVisitor(IJavaProject project, BuildingContext context) {
			this.project = project;
			this.context = context;
		}

		private void grabJavaFileHierarchy(IResource resource) throws JavaModelException, CoreException {
			IFile resourceFile = (IFile) resource;
			File file = new File(resource.getProject().getLocation().toFile(),
					resourceFile.getProjectRelativePath().toFile().toString());
			if (!javaFiles.contains(file)) {
				javaFiles.add(file);
				javaResourceFiles.add(resourceFile);
				IPackageFragment packge = project.findPackageFragment(resource.getParent().getFullPath());
				if (packge != null && packge.getCompilationUnits() != null) {
					for (ICompilationUnit cu : packge.getCompilationUnits()) {
						if (cu.getResource().equals(resource)) {
							for (IType t1 : cu.getAllTypes()) {
								for (IType t2 : t1.newTypeHierarchy(project, null).getAllTypes()) {
									IResource r = t2.getResource();
									if (r instanceof IFile && resource.getName().endsWith(".java")) {
										resourceFile = (IFile) r;
										file = new File(r.getProject().getLocation().toFile(),
												resourceFile.getProjectRelativePath().toFile().toString());
										if (!javaFiles.contains(file)) {
											javaFiles.add(file);
											javaResourceFiles.add(resourceFile);
										}
									}
								}

							}
						}
					}
				}
			}
		}

		public boolean visit(IResourceDelta delta) throws CoreException {
			IResource resource = delta.getResource();
			if (resource instanceof IFile && resource.getName().endsWith(".java")) {
				switch (delta.getKind()) {
				case IResourceDelta.ADDED:
					if (context.USE_WATCH_MODE && context.transpiler != null) {
						context.transpiler.resetTscWatchMode();
					}
					grabJavaFileHierarchy(resource);
					break;
				case IResourceDelta.REMOVED:
					deleteMarkers((IFile) resource);
					SourceFile sf;
					if (context.USE_WATCH_MODE && context.transpiler != null) {
						File file = new File(resource.getProject().getLocation().toFile(),
								((IFile) resource).getProjectRelativePath().toFile().toString());
						sf = context.transpiler.getWatchedFile(file);
						context.transpiler.resetTscWatchMode();
					} else {
						File file = new File(resource.getProject().getLocation().toFile(),
								((IFile) resource).getProjectRelativePath().toFile().toString());
						sf = context.sourceFiles.get(file);
						context.sourceFiles.remove(file);
					}
					if (sf != null) {
						File tsFile = sf.getTsFile();
						File jsFile = sf.getJsFile();
						File jsMapFile = sf.getJsFile();
						FileUtils.deleteQuietly(tsFile);
						FileUtils.deleteQuietly(jsFile);
						FileUtils.deleteQuietly(jsMapFile);
					}
					break;
				case IResourceDelta.CHANGED:
					grabJavaFileHierarchy(resource);
					break;
				}
			}
			// return true to continue visiting children.
			return true;
		}
	}

	private static boolean isIncluded(BuildingContext context, IPath path) {
		if (!StringUtils.isBlank(Preferences.getSourceIncludeFilter(context.project, context.profile))) {
			if (!path.toString().matches(Preferences.getSourceIncludeFilter(context.project, context.profile))) {
				Log.info("excluded by included filer: " + path);
				return false;
			}
		}
		if (!StringUtils.isBlank(Preferences.getSourceExcludeFilter(context.project, context.profile))) {
			if (path.toString().matches(Preferences.getSourceExcludeFilter(context.project, context.profile))) {
				Log.info("excluded by excluded filer: " + path);
				return false;
			}
		}
		Log.info("include: " + path);
		return true;
	}

	class GrabJavaFilesVisitor implements IResourceVisitor {
		private BuildingContext context;
		public List<File> javaFiles = new ArrayList<File>();
		public List<IPath> sourceDirs;

		public GrabJavaFilesVisitor(BuildingContext context, List<IPath> sourceDirs) {
			this.context = context;
			this.sourceDirs = sourceDirs;
		}

		public boolean visit(IResource resource) {
			if (resource instanceof IFile && resource.getName().endsWith(".java")) {
				IFile file = (IFile) resource;
				if (file.getProjectRelativePath().segment(0).equals(JSweetTranspiler.TMP_WORKING_DIR_NAME)) {
					return true;
				}
				if (!sourceDirs.isEmpty()) {
					for (IPath sourcePath : sourceDirs) {
						if (sourcePath.isPrefixOf(file.getFullPath())) {
							IPath relativePath = file.getFullPath().makeRelativeTo(sourcePath);
							if (isIncluded(context, relativePath)) {
								javaFiles.add(new File(resource.getProject().getLocation().toFile(),
										file.getProjectRelativePath().toFile().toString()));
							}
							return true;
						}
					}
				} else {
					if (isIncluded(context, file.getProjectRelativePath())) {
						javaFiles.add(new File(resource.getProject().getLocation().toFile(),
								file.getProjectRelativePath().toFile().toString()));
					}
				}
			}
			// return true to continue visiting children.
			return true;
		}
	}

	class JSweetTranspilationHandler implements TranspilationHandler {

		BuildingContext context;

		public JSweetTranspilationHandler(BuildingContext context) {
			this.context = context;
		}

		@Override
		public void report(JSweetProblem problem, SourcePosition sourcePosition, String message) {
			if (problem == JSweetProblem.INTERNAL_JAVA_ERROR) {
				// ignore Java errors because they will be reported by Eclipse
				return;
			}
			String base = context.project.getLocation().toFile().getAbsolutePath();
			if (sourcePosition == null || sourcePosition.getFile() == null) {
				addMarker(context.project, message, -1, -1, -1,
						problem.getSeverity() == Severity.ERROR ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING);
			} else {
				IFile f = null;
				try {
					f = (IFile) context.project
							.findMember(sourcePosition.getFile().getAbsolutePath().substring(base.length() + 1));
				} catch (Exception e) {
					Log.error(message, e);
					// swallow
				}
				if (f == null) {
					try {
						f = (IFile) context.project.findMember(sourcePosition.getFile().getPath());
					} catch (Exception e) {
						Log.error(message, e);
						// swallow
					}
				}
				if (f == null) {
					addMarker(context.project, message, -1, -1, -1, problem.getSeverity() == Severity.ERROR
							? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING);
				} else {
					addMarker(f, message, sourcePosition.getStartLine(),
							sourcePosition.getStartPosition().getPosition(),
							sourcePosition.getEndPosition().getPosition(), problem.getSeverity() == Severity.ERROR
									? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING);
				}
			}
		}

		@Override
		public void onCompleted(JSweetTranspiler transpiler, boolean fullPass, SourceFile[] files) {
			try {
				if (fullPass) {
					Log.info("refreshing worspace (full)");
					context.project.refreshLocal(IResource.DEPTH_INFINITE, null);
				} else {
					// TODO: refresh only the resources that have changed (using
					// the files argument)
					Log.info("refreshing output directories (incremental)");
					final IResource output = context.project
							.findMember(Preferences.getTsOutputFolder(context.project, context.profile));
					final IResource jsOutput = context.project
							.findMember(Preferences.getJsOutputFolder(context.project, context.profile));
					if (output != null || jsOutput != null) {
						new Thread() {
							public void run() {
								try {
									if (output != null) {
										output.refreshLocal(IResource.DEPTH_INFINITE, null);
									}
									if (jsOutput != null && !jsOutput.equals(output)) {
										jsOutput.refreshLocal(IResource.DEPTH_INFINITE, null);
									}
								} catch (CoreException e) {
									Log.error("error while refreshing", e);
								}
							}
						}.start();
					}
				}

			} catch (Exception e) {
				Log.error(e);
			}
		}

	}

	private void addMarker(IResource resource, String message, int lineNumber, int charStart, int charEnd,
			int severity) {
		try {
			IMarker marker = resource.createMarker(JSWEET_PROBLEM_MARKER_TYPE);
			marker.setAttribute(IMarker.MESSAGE, message);
			marker.setAttribute(IMarker.SEVERITY, severity);
			if (lineNumber >= 0) {
				marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
			}
			if (charStart >= 0) {
				marker.setAttribute(IMarker.CHAR_START, charStart);
			}
			if (charEnd >= 0) {
				marker.setAttribute(IMarker.CHAR_END, charEnd);
			}
		} catch (CoreException e) {
			Log.error(e);
		}
	}

	protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
		// Only full builds are supported for now... incremental builds should
		// be supported later if possible

		// addResourceChangeListener();
		// if (!USE_WATCH_MODE || kind == FULL_BUILD) {
		// fullBuild(monitor);
		// } else {
		// IResourceDelta delta = getDelta(getProject());
		// if (delta == null) {
		// fullBuild(monitor);
		// } else {
		// incrementalBuild(delta, monitor);
		// }
		// }
		for (String profile : Preferences.parseProfiles(getProject())) {
			fullBuild(new BuildingContext(getProject(), profile), monitor);
		}
		forceStaticImports();
		return null;
	}

	private String[] defaultFavorites = { JSweetConfig.LANG_PACKAGE + "." + JSweetConfig.GLOBALS_CLASS_NAME + ".*",
			JSweetConfig.UTIL_CLASSNAME + ".*", JSweetConfig.DOM_PACKAGE + "." + JSweetConfig.GLOBALS_CLASS_NAME + ".*",
			JSweetConfig.LIBS_PACKAGE + ".jquery." + JSweetConfig.GLOBALS_CLASS_NAME + ".*",
			JSweetConfig.LIBS_PACKAGE + ".underscore." + JSweetConfig.GLOBALS_CLASS_NAME + ".*" };

	private void forceStaticImports() {
		try {
			IPreferenceStore s = new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.eclipse.jdt.ui");
			String favorites = s.getString(PreferenceConstants.CODEASSIST_FAVORITE_STATIC_MEMBERS);
			for (String f : defaultFavorites) {
				if (!favorites.contains(f)) {
					if (!favorites.isEmpty()) {
						favorites += ";";
					}
					favorites += f;
				}
			}
			Log.info("forcing favorite static members: " + favorites);
			s.setValue(PreferenceConstants.CODEASSIST_FAVORITE_STATIC_MEMBERS, favorites);
		} catch (Exception e) {
			Log.error(e);
		}
	}

	@Override
	protected void clean(IProgressMonitor monitor) throws CoreException {
		clean(getProject(), monitor);
	}

	private void deleteMarkers(IFile... files) {
		try {
			for (IFile file : files) {
				file.deleteMarkers(JSWEET_PROBLEM_MARKER_TYPE, false, IResource.DEPTH_ZERO);
			}
		} catch (CoreException ce) {
			Log.error(ce);
		}
	}

	private void transpileFiles(BuildingContext context, File... files) {
		try {
			if (context.transpiler == null || files == null || files.length == 0) {
				return;
			}
			Log.info("compiling " + Arrays.asList(files));
			SourceFile[] sfs = SourceFile.toSourceFiles(files);
			for (SourceFile sf : sfs) {
				context.sourceFiles.put(sf.getJavaFile(), sf);
			}
			context.transpiler.transpile(new JSweetTranspilationHandler(context), sfs);
		} catch (Throwable t) {
			Log.error("cannot compile", t);
		}
	}

	private void fullBuild(BuildingContext context, final IProgressMonitor monitor) throws CoreException {
		Log.info("JSweet: full build...");
		context.project.deleteMarkers(JSWEET_PROBLEM_MARKER_TYPE, true, IResource.DEPTH_INFINITE);
		List<IPath> sourceDirs = new ArrayList<>();
		if (!StringUtils.isEmpty(Preferences.getSourceFolders(context.project, context.profile))) {
			String[] names = Preferences.getSourceFolders(context.project, context.profile).split("[,;]");
			try {
				for (String name : names) {
					sourceDirs.add(context.project.getFolder(name).getFullPath());
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		String jdkHome = null;
		boolean lookupSourceFolder = sourceDirs.isEmpty();
		if (context.project.isNatureEnabled("org.eclipse.jdt.core.javanature")) {
			IJavaProject javaProject = JavaCore.create(context.project);
			IClasspathEntry[] classPathEntries = javaProject.getResolvedClasspath(true);
			for (IClasspathEntry e : classPathEntries) {
				if (lookupSourceFolder && e.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					sourceDirs.add(e.getPath());
				} else if (e.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
					if (e.getPath().toString().endsWith("/lib/rt.jar")) {
						jdkHome = e.getPath().removeLastSegments(2).toString();
					}
				}
			}
		}
		Log.info("source dirs: " + sourceDirs);
		GrabJavaFilesVisitor v = new GrabJavaFilesVisitor(context, sourceDirs);

		context.project.accept(v);
		context.sourceFiles.clear();
		Log.info("init classpath, jdkHome: " + jdkHome);
		JSweetConfig.initClassPath(jdkHome);
		createJSweetTranspiler(context);
		transpileFiles(context, v.javaFiles.toArray(new File[0]));
	}

	private class CheckIfRemovedInDelta implements IResourceDeltaVisitor {
		public boolean result = false;

		@Override
		public boolean visit(IResourceDelta delta) throws CoreException {
			if (delta.getKind() == IResourceDelta.REMOVED) {
				result = true;
			}
			return true;
		}
	}

	protected void incrementalBuild(BuildingContext context, IResourceDelta delta, IProgressMonitor monitor)
			throws CoreException {
		if (context.project.isNatureEnabled("org.eclipse.jdt.core.javanature")) {
			Log.info("JSweet: incremental build...");
			CheckIfRemovedInDelta removed = new CheckIfRemovedInDelta();
			delta.accept(removed);
			if (removed.result) {
				// we should delete only the removed file
				cleanFiles(context);
				fullBuild(context, monitor);
			} else {
				IJavaProject javaProject = JavaCore.create(context.project);
				IncrementalGrabJavaFileVisitor v = new IncrementalGrabJavaFileVisitor(javaProject, context);
				delta.accept(v);
				deleteMarkers(v.javaResourceFiles.toArray(new IFile[0]));
				context.project.deleteMarkers(JSWEET_PROBLEM_MARKER_TYPE, true, IResource.DEPTH_ZERO);
				if (context.transpiler == null) {
					createJSweetTranspiler(context);
				}
				transpileFiles(context, v.javaFiles.toArray(new File[0]));
			}
		} else {
			// no support for incremental build
			fullBuild(context, monitor);
		}

	}

	private void createJSweetTranspiler(BuildingContext context) throws CoreException {
		if (context.USE_WATCH_MODE && context.transpiler != null) {
			Log.info("stopping tsc watch mode");
			context.transpiler.setTscWatchMode(false);
			Log.info("tsc watch mode stopped");
		}
		StringBuilder classPath = new StringBuilder();
		if (context.project.isNatureEnabled("org.eclipse.jdt.core.javanature")) {
			IJavaProject javaProject = JavaCore.create(context.project);
			IClasspathEntry[] classPathEntries = javaProject.getResolvedClasspath(true);
			for (IClasspathEntry e : classPathEntries) {
				classPath.append(resolve(context.project, e.getPath()).toString());
				classPath.append(File.pathSeparator);
			}
		}
		Log.info("compiling with classpath: " + classPath.toString());
		File jsOutputFolder = new File(context.project.getLocation().toFile(),
				Preferences.getJsOutputFolder(context.project, context.profile));
		try {
			JSweetFactory factory = new JSweetFactory();
			context.transpiler = new JSweetTranspiler(factory,
					new File(context.project.getLocation().toFile(), JSweetTranspiler.TMP_WORKING_DIR_NAME),
					new File(context.project.getLocation().toFile(),
							Preferences.getTsOutputFolder(context.project, context.profile)),
					jsOutputFolder,
					new File(context.project.getLocation().toFile(),
							Preferences.getCandyJsOutputFolder(context.project, context.profile)),
					classPath.toString());
			context.transpiler.setGenerateJsFiles(!Preferences.getNoJs(context.project, context.profile));
			context.transpiler
					.setPreserveSourceLineNumbers(Preferences.isJavaDebugMode(context.project, context.profile));
			String moduleString = Preferences.getModuleKind(context.project, context.profile);
			context.transpiler.setModuleKind(
					StringUtils.isBlank(moduleString) ? ModuleKind.none : ModuleKind.valueOf(moduleString));
			String bundleDirectory = Preferences.getBundlesDirectory(context.project, context.profile);
			if (!StringUtils.isBlank(bundleDirectory) && Preferences.getBundle(context.project, context.profile)) {
				File f = new File(bundleDirectory);
				if (!f.isAbsolute()) {
					f = new File(context.project.getLocation().toFile(), bundleDirectory);
				}
				context.transpiler.setJsOutputDir(f);
			}
			context.transpiler.setBundle(Preferences.getBundle(context.project, context.profile));
			context.transpiler.setGenerateDeclarations(Preferences.getDeclaration(context.project, context.profile));
			String declarationDirectory = Preferences.getDeclarationDirectory(context.project, context.profile);
			if (!StringUtils.isBlank(declarationDirectory)) {
				File f = new File(declarationDirectory);
				if (!f.isAbsolute()) {
					f = new File(context.project.getLocation().toFile(), declarationDirectory);
				}
				context.transpiler.setDeclarationsOutputDir(f);
			}

			// transpiler.setTsDefDirs(new
			// File(context.project.getLocation().toFile(),
			// Preferences
			// .getTsOutputFolder(context.project)));
			if (context.USE_WATCH_MODE) {
				context.transpiler.setTscWatchMode(true);
			}
			Log.info("created JSweet transpiler: " + context.transpiler);
		} catch (NoClassDefFoundError error) {
			new JSweetTranspilationHandler(context).report(JSweetProblem.JAVA_COMPILER_NOT_FOUND, null,
					JSweetProblem.JAVA_COMPILER_NOT_FOUND.getMessage());
		}
	}

	private static File resolve(IProject project, IPath path) {
		String string = path.toString();
		if (path.isAbsolute()) {
			File f = new File(string);
			if (f.exists()) {
				return f;
			} else {
				return new File(project.getWorkspace().getRoot().getLocation().toFile(), string);
			}
		} else {
			return new File(project.getLocation().toFile(), string);
		}
	}

}
