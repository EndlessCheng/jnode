/*
 * $Id$
 *
 * JNode.org
 * Copyright (C) 2003-2006 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.jnode.shell;

import java.io.File;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.DateFormat;
import java.util.Date;
import java.util.StringTokenizer;

import javax.naming.NameNotFoundException;

import org.apache.log4j.Logger;
import org.jnode.driver.console.InputHistory;
import org.jnode.driver.console.CompletionInfo;
import org.jnode.driver.console.ConsoleManager;
import org.jnode.driver.console.TextConsole;
import org.jnode.driver.console.ConsoleListener;
import org.jnode.driver.console.ConsoleEvent;
import org.jnode.driver.console.textscreen.KeyboardInputStream;
import org.jnode.naming.InitialNaming;
import org.jnode.shell.alias.AliasManager;
import org.jnode.shell.alias.NoSuchAliasException;
import org.jnode.shell.help.CompletionException;
import org.jnode.util.SystemInputStream;
import org.jnode.vm.VmSystem;

/**
 * @author epr
 * @author Fabien DUMINY
 * @authod crawley
 */
public class CommandShell implements Runnable, Shell, ConsoleListener {

    public static final String PROMPT_PROPERTY_NAME = "jnode.prompt";
	public static final String INTERPRETER_PROPERTY_NAME = "jnode.interpreter";
	public static final String INVOKER_PROPERTY_NAME = "jnode.invoker";
	public static final String CMDLINE_PROPERTY_NAME = "jnode.cmdline";
	public static final String DEBUG_PROPERTY_NAME = "jnode.debug";
	public static final String HISTORY_PROPERTY_NAME = "jnode.history";
	
	public static final String HOME_PROPERTY_NAME = "user.home";
	public static final String DIRECTORY_PROPERTY_NAME = "user.dir";

	public static final String INITIAL_INVOKER = "thread";
	public static final String INITIAL_INTERPRETER = "redirecting";
	public static final String FALLBACK_INVOKER = "default";
	public static final String FALLBACK_INTERPRETER = "default";

	private static String DEFAULT_PROMPT = "JNode $P$G";
    private static final String COMMAND_KEY = "cmd=";

    /**
     * My logger
     */
    private static final Logger log = Logger.getLogger(CommandShell.class);

    private PrintStream out;
    
    private PrintStream err;
    
    private InputStream in;

    private AliasManager aliasMgr;

    /**
     * Keeps a reference to the console this CommandShell is using *
     */
    private TextConsole console;

    /**
     * Contains the archive of commands. *
     */
    private InputHistory commandHistory = new InputHistory();
    
    /**
     * Contains the application input history for the current thread.
     */
    private static InheritableThreadLocal<InputHistory> applicationHistory = 
    	new InheritableThreadLocal<InputHistory> ();
    
    private boolean readingCommand;

    /**
     * Contains the last command entered
     */
    private String lastCommandLine = "";

    /**
     * Contains the last application input line entered
     */
    private String lastInputLine = "";

    /**
     * Flag to know when to wait (while input is happening). This is (hopefully)
     * a thread safe implementation. *
     */
    private volatile boolean threadSuspended = false;

    private CommandInvoker invoker;
    private String invokerName;

    private CommandInterpreter interpreter;
    private String interpreterName;

    private CompletionInfo completion;
    
    private boolean historyEnabled;

    private boolean debugEnabled;

    private boolean exitted = false;

    private Thread ownThread;

    
    public TextConsole getConsole() {
        return console;
    }
    
    public static void main(String[] args) throws NameNotFoundException, ShellException {
    	CommandShell shell = new CommandShell();
        shell.run();
    }

    /**
     * Create a new instance
     * 
     * @see java.lang.Object
     */
    public CommandShell() throws NameNotFoundException, ShellException {
        this((TextConsole) ((ConsoleManager) InitialNaming
                .lookup(ConsoleManager.NAME)).getFocus());
    }

    public CommandShell(TextConsole cons) throws ShellException {
    	try {
            this.console = cons;
            this.out = this.console.getOut();
            this.err = this.console.getErr();
        	this.in = this.console.getIn();
        	SystemInputStream.getInstance().initialize(this.in);
        	cons.setCompleter(this);

            this.console.addConsoleListener(this);
            // threads for commands.
            aliasMgr = ((AliasManager) InitialNaming.lookup(AliasManager.NAME))
                    .createAliasManager();
            System.setProperty(PROMPT_PROPERTY_NAME, DEFAULT_PROMPT);
        	// ShellUtils.getShellManager().registerShell(this);
        } catch (NameNotFoundException ex) {
            throw new ShellException("Cannot find required resource", ex);
        }
        catch (Exception ex) {
        	ex.printStackTrace();
        }
    }

    /**
     * Run this shell until exit.
     * 
     * @see java.lang.Runnable#run()
     */
    public void run() {
        // Here, we are running in the CommandShell (main) Thread
        // so, we can register ourself as the current shell
        // (it will also be the current shell for all children Thread)
    	
    	// FIXME - At one point, the 'current shell' had something to do with
    	// dispatching keyboard input to the right application.  Now this is
    	// handled by the console layer.  Is 'current shell' a meaningful / 
    	// useful concept anymore?
        try {
            ShellUtils.getShellManager().registerShell(this);
    		
            ShellUtils.registerCommandInvoker(DefaultCommandInvoker.FACTORY);
    		ShellUtils.registerCommandInvoker(ThreadCommandInvoker.FACTORY);
    		ShellUtils.registerCommandInvoker(ProcletCommandInvoker.FACTORY);
    		ShellUtils.registerCommandInterpreter(DefaultInterpreter.FACTORY);
    		ShellUtils.registerCommandInterpreter(RedirectingInterpreter.FACTORY);
        } catch (NameNotFoundException e1) {
            e1.printStackTrace();
        }

        // Configure the shell based on Syetsm properties.
        setupFromProperties();

        // Now become interactive
    	ownThread = Thread.currentThread();
    	
        // Run commands from the JNode commandline first
        final String cmdLine = System.getProperty(CMDLINE_PROPERTY_NAME, "");
        final StringTokenizer tok = new StringTokenizer(cmdLine);

        while (tok.hasMoreTokens()) {
            final String e = tok.nextToken();
            try {
                if (e.startsWith(COMMAND_KEY)) {
                    final String cmd = e.substring(COMMAND_KEY.length());
                    out.println(prompt() + cmd);
                    processCommand(cmd, false);
                }
            } catch (Throwable ex) {
            	err.println("Error while processing bootarg commands: " + ex.getMessage());
            	stackTrace(ex);
            }
        }

        final String user_home = System.getProperty(HOME_PROPERTY_NAME, "");

        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            public Void run() {
                    final File shell_ini = new File(user_home + "/shell.ini");
                try {
                    if (shell_ini.exists()) {
                    executeFile(shell_ini);
                    }
                } catch (IOException ex) {
                	err.println("Error while reading " + shell_ini + ": " + ex.getMessage());
                	stackTrace(ex);
                }
                return null;
            }
        });

        while (!isExitted()) {
            try {
            	refreshFromProperties();
            	
            	clearEof();
            	out.print(prompt());
            	readingCommand = true;
            	String line = readInputLine().trim();
            	if (line.length() > 0) {
            		processCommand(line, true);
            	}

            	if (VmSystem.isShuttingDown()) {
            		exitted = true;
            	}
            } catch (Throwable ex) {
            	err.println("Uncaught exception while processing command(s): " + ex.getMessage());
            	stackTrace(ex);
            }
        }
    }
    
    private void setupFromProperties() {
    	debugEnabled = Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY_NAME, "true"));
    	historyEnabled = Boolean.parseBoolean(System.getProperty(HISTORY_PROPERTY_NAME, "true"));
    	try {
        	setCommandInvoker(System.getProperty(INVOKER_PROPERTY_NAME, INITIAL_INVOKER));
        } catch (Exception ex) {
        	err.println(ex.getMessage());
        	stackTrace(ex);
        	setCommandInvoker(FALLBACK_INVOKER);  // fallback to default
        }
        try {
        	setCommandInterpreter(System.getProperty(INTERPRETER_PROPERTY_NAME, INITIAL_INTERPRETER));
        } catch (Exception ex) {
        	err.println(ex.getMessage());
        	stackTrace(ex);
        	setCommandInterpreter(FALLBACK_INTERPRETER);  // fallback to default
        }
    }
    
    private void refreshFromProperties() {
    	debugEnabled = Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY_NAME, "true"));
    	historyEnabled = Boolean.parseBoolean(System.getProperty(HISTORY_PROPERTY_NAME, "true"));
    	try {
    		setCommandInterpreter(System.getProperty(INTERPRETER_PROPERTY_NAME, ""));
    	}
    	catch (Exception ex) {
    		err.println(ex.getMessage());
        	stackTrace(ex);
    	}
    	try {
    		setCommandInvoker(System.getProperty(INVOKER_PROPERTY_NAME, ""));
    	}
    	catch (Exception ex) {
    		err.println(ex.getMessage());
        	stackTrace(ex);
    	}
            }
    
    public synchronized void setCommandInvoker(String name)
    throws IllegalArgumentException {
    	if (!name.equals(this.invokerName)) {
    		this.invoker = ShellUtils.createInvoker(name, this);
    		err.println("Switched to " + name + " invoker");
    		this.invokerName = name;
            System.setProperty(INVOKER_PROPERTY_NAME, name);
    	}
    }
    
    public synchronized void setCommandInterpreter(String name) 
    throws IllegalArgumentException {
    	if (!name.equals(this.interpreterName)) {
    		this.interpreter = ShellUtils.createInterpreter(name);
    		err.println("Switched to " + name + " interpreter");
            this.interpreterName = name;
            System.setProperty(INTERPRETER_PROPERTY_NAME, name);
    	}
    }
    
    private void stackTrace(Throwable ex) {
    	if (this.debugEnabled) {
    		ex.printStackTrace(err);
        }
    }
    
    private String readInputLine() throws IOException {
    	StringBuffer sb = new StringBuffer(40);
    	Reader r = new InputStreamReader(in);
    	while (true) {
    		int ch = r.read();
    		if (ch == -1 || ch == '\n') {
    			return sb.toString();
    		}
    		sb.append((char) ch);
    	}
    }
    
    private void clearEof() {
    	if (in instanceof KeyboardInputStream) {
    	    ((KeyboardInputStream) in).clearSoftEOF();
    	}
    }

    protected void processCommand(String cmdLineStr, boolean interactive) {
    	clearEof();
    	if (interactive) {
        	readingCommand = false;
        	// Each interactive command is launched with a fresh history
        	// for input completion
        	applicationHistory.set(new InputHistory());
    	}
    	try {
    		interpreter.interpret(this, cmdLineStr);
    	}
    	catch (ShellException ex) {
    		err.println("Shell exception: " + ex.getMessage());
        	stackTrace(ex);
    	}
    		
    	if (interactive) {
        	applicationHistory.set(null);
    	}
    }

    /**
     * Parse and run a command line using the CommandShell's current interpreter.
     * @param command the command line.
     * @throws ShellException
     */
    public void invokeCommand(String command) throws ShellException {
        processCommand(command, false);
    }

    /**
     * Run a command encoded as a CommandLine object.  The command line
     * will give the command name (alias), the argument list and the
     * IO stream.  The command is run using the CommandShell's current invoker.
     * 
     * @param cmdLine the CommandLine object.
     * @return the command's return code
     * @throws ShellException
     */
    public int invoke(CommandLine cmdLine) throws ShellException {
    	return this.invoker.invoke(cmdLine);
    }

    /**
     * Prepare a CommandThread to run a command encoded as a CommandLine object.
     * When the thread's "start" method is called, the command will be executed
     * using the CommandShell's current (now) invoker.
     * 
     * @param cmdLine the CommandLine object.
     * @return the command's return code
     * @throws ShellException
     */
    public CommandThread invokeAsynchronous(CommandLine cmdLine) 
    throws ShellException {
    	return this.invoker.invokeAsynchronous(cmdLine);
    }

    protected CommandInfo getCommandClass(String cmd)
            throws ClassNotFoundException {
        try {
            Class cls = aliasMgr.getAliasClass(cmd);
            return new CommandInfo(cls, aliasMgr.isInternal(cmd));
        } catch (NoSuchAliasException ex) {
            final ClassLoader cl = Thread.currentThread()
                    .getContextClassLoader();
            return new CommandInfo(cl.loadClass(cmd), false);
        }
    }

    boolean isDebugEnabled() {
    	return debugEnabled;
    }

    /**
     * Gets the alias manager of this shell
     */
    public AliasManager getAliasManager() {
        return aliasMgr;
    }

    /**
     * Gets the shell's command InputHistory object.
     */
    public InputHistory getCommandHistory() {
    	return commandHistory;
    }

    /**
     * Gets the shell's currently active InputHistory object.
     */
    public InputHistory getInputHistory() {
    	if (readingCommand) {
            return commandHistory;
    	}
    	else {
    		return CommandShell.applicationHistory.get();
    	}
    }

    /**
     * Gets the expanded prompt
     */
    protected String prompt() {
        String prompt = System.getProperty(PROMPT_PROPERTY_NAME, DEFAULT_PROMPT);
        final StringBuffer result = new StringBuffer();
        boolean commandMode = false;
        try {
            StringReader reader = new StringReader(prompt);
            int i;
            while ((i = reader.read()) != -1) {
                char c = (char) i;
                if (commandMode) {
                    switch (c) {
                    case 'P':
                        result.append(new File(
                        		System.getProperty(DIRECTORY_PROPERTY_NAME, "")));
                        break;
                    case 'G':
                        result.append("> ");
                        break;
                    case 'D':
                        final Date now = new Date();
	                        DateFormat.getDateTimeInstance().format(now, result, null);
                        break;
                    default:
                        result.append(c);
                    }
                    commandMode = false;
                } else {
                    switch (c) {
                    case '$':
                        commandMode = true;
                        break;
                    default:
                        result.append(c);
                    }
                }
            }
        } catch (Exception ioex) {
            // This should never occur
            log.error("Error in prompt()", ioex);
        }
        return result.toString();
    }
    
    public Completable parseCommandLine(String cmdLineStr) throws ShellSyntaxException {
    	return interpreter.parsePartial(this, cmdLineStr);
    }

    public CompletionInfo complete(String partial) {
        if (!readingCommand) {
        	// dummy completion behavior for application input.
        	CompletionInfo completion = new CompletionInfo();
            completion.setCompleted(partial);
            completion.setNewPrompt(true);
        	return completion;
        }
        
        // workaround to set the currentShell to this shell
        try {
            ShellUtils.getShellManager().registerShell(this);
        } catch (NameNotFoundException ex) {
        }

        // do command completion
        completion = new CompletionInfo();
        boolean success = false;
        try {
        	Completable cl = parseCommandLine(partial);
        	if (cl != null) {
        		cl.complete(completion, this);
        		if (!partial.equals(completion.getCompleted()) && !completion.hasItems()) {
        			// we performed direct completion without listing
                completion.setNewPrompt(false);
            }
        		success = true;
        	}
        } catch (ShellSyntaxException ex) {
            out.println(); // next line
            err.println("Cannot parse: " + ex.getMessage()); // print the error (optional)
            
        } catch (CompletionException ex) {
            out.println(); // next line
        	err.println("Problem in completer: " + ex.getMessage()); // print the error (optional)
        }

    	if (!success) {
        	// Make sure the caller knows to repaint the prompt
            completion.setCompleted(partial);
            completion.setNewPrompt(true);
        }

        // Make sure that the shell's completion context gets nulled.
        CompletionInfo myCompletion = completion;
        completion = null;
        return myCompletion;
    }

    public void list(String[] items) {
        if (completion == null) {
        	throw new ShellFailureException("list called when no completion is in progress");
        }
        else {
        completion.setItems(items);
    }
    }

    public void addCommandToHistory(String cmdLineStr) {
        // Add this command to the command history.
        if (isHistoryEnabled() && !cmdLineStr.equals(lastCommandLine)) {
            commandHistory.addLine(cmdLineStr);
            lastCommandLine = cmdLineStr;
        }
    }

    public void addInputToHistory(String inputLine) {
        // Add this input to the application input history.
        if (isHistoryEnabled() && !inputLine.equals(lastInputLine)) {
            InputHistory history = applicationHistory.get();
            if (history != null) {
            	history.addLine(inputLine);
            	lastInputLine = inputLine;
            }
        }
    }

    public InputStream getInputStream() {
    	if (isHistoryEnabled()) {
    		// Insert a filter on the input stream that adds completed input lines
    		// to the application input history.
    		return new HistoryInputStream(in);
    	}
    	else {
            return in;
    	}
    }
    
    /**
     * This class subtypes FilterInputStream to capture console input to an
     * application in the application input history.
     */
    private class HistoryInputStream extends FilterInputStream {
		// TODO - revisit for support of multi-byte character encodings.
		private StringBuilder line = new StringBuilder();
		
		public HistoryInputStream(InputStream in) {
			super(in);
		}
		
		@Override
		public int read() throws IOException {
			int res = super.read();
			if (res != -1) {
				filter((byte) res);
			}
			return res;
		}

		@Override
		public int read(byte[] buf, int offset, int len) throws IOException {
			int res = super.read(buf, offset, len);
			for (int i = 0; i < res; i++) {
				filter(buf[offset + i]);
			}
			return res;
		}

		@Override
		public int read(byte[] buf) throws IOException {
			int res = super.read(buf);
			for (int i = 0; i < res; i++) {
				filter(buf[i]);
			}
			return res;
		}
		
		private void filter(byte b) {
			if (b == '\n') {
				addInputToHistory(line.toString());
				line.setLength(0);
			}
			else {
				line.append((char) b);
			}
		}
	}

    public PrintStream getOutputStream() {
        return out;
    }

    public PrintStream getErrorStream() {
        return err;
    }

    public CommandInvoker getDefaultCommandInvoker() {
    	return ShellUtils.createInvoker("default", this);
    }

    public void executeFile(File file) throws IOException {
        if (!file.exists()) {
            err.println( "File does not exist: " + file);
            return;
        }
        try {
            setHistoryEnabled(false);
            final BufferedReader br = new BufferedReader(new FileReader(file));
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                line = line.trim();

                if (line.startsWith("#") || line.equals("")) {
                    continue;
                }
                try {
                invokeCommand(line);
            }
                catch (ShellException ex) {
                	err.println("Shell exception: " + ex.getMessage());
                	stackTrace(ex);
                }
            }
            br.close();
        } finally {
            setHistoryEnabled(true);
        }
    }

    public void exit(){
        exit0();
        console.close();
    }

    public void consoleClosed(ConsoleEvent event) {
        if (!exitted) {
            if (Thread.currentThread() == ownThread){
                exit0();
            } else {
                synchronized(this) {
                    exit0();
                    notifyAll();
                }
            }
        }
    }

    private void exit0() {
        exitted = true;
        threadSuspended = false;
    }

    private synchronized boolean isExitted() {
        return exitted;
    }

    private boolean isHistoryEnabled() {
        return historyEnabled;
    }

    private void setHistoryEnabled(boolean historyEnabled) {
        this.historyEnabled = historyEnabled;
    }
}

