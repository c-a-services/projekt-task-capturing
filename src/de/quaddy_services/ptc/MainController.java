/**
 * 
 */
package de.quaddy_services.ptc;

import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicBorders;

import de.quaddy_services.ptc.edit.TaskEditor;
import de.quaddy_services.ptc.enterprise.EnterpriseUtil;
import de.quaddy_services.ptc.enterprise.EnterpriseUtilRemote;
import de.quaddy_services.ptc.log.Log;
import de.quaddy_services.ptc.preferences.PreferencesSelection;
import de.quaddy_services.ptc.store.BackupFile;
import de.quaddy_services.ptc.store.FileUtil;
import de.quaddy_services.ptc.store.PosAndContent;
import de.quaddy_services.ptc.store.Task;
import de.quaddy_services.ptc.store.TaskHistory;
import de.quaddy_services.ptc.store.TaskUpdater;
import de.quaddy_services.report.TaskReport;
import de.quaddy_services.report.groupby.GroupBy;
import de.quaddy_services.report.groupby.GroupByList;
import de.quaddy_services.report.gui.ReportSelection;

/**
 * @author Stefan Cordes
 * 
 */
public class MainController {
	private Log LOG = new Log(this.getClass());
	private MainModel model;

	private JFrame frame;

	private Timer repeatingTimer;

	private MainView mainView;

	private boolean initialized = false;

	private volatile boolean shutdownPending = false;

	public void init() {
		LOG.info("init");
		LOG.info("java.runtime.name=" + System.getProperty("java.runtime.name"));
		LOG.info("java.runtime.version=" + System.getProperty("java.runtime.version"));
		loadModel();
		mainView = new MainView();
		mainView.setController(this);

		initEnterpriseServer();

		try {
			taskHistory.backupFile();
			taskHistory.updateLastTask(TaskHistory.TASK_STARTED);
			setMainViewModel();
		} catch (Throwable e) {
			handleException(e);
		}

		initFrame("PTC", mainView);
		repeatingTimer = new Timer(10000, new ActionListener() {
			public void actionPerformed(ActionEvent aE) {
				timerRepeats();
			}
		});
		repeatingTimer.start();

		Thread tempShutdownPendingDetector = new Thread() {
			@Override
			public void run() {
				// Do nothing here but setting the variable. No logging etc!
				shutdownPending = true;
			}
		};
		tempShutdownPendingDetector.setName(tempShutdownPendingDetector.getName() + "ShutdownPendingDetector");
		Runtime.getRuntime().addShutdownHook(tempShutdownPendingDetector);
	}

	private void setMainViewModel() throws IOException {
		List<String> tempLastTasks = taskHistory.getLastTasks();
		enterpriseUtil.filterWithFixedTasks(model, tempLastTasks);
		if (tempLastTasks.size() == 0) {
			// Very first start of PTC.
			tempLastTasks.add(model.getDontSumChar().getChar() + "Enter your task here");
		}
		model.setCurrentTask(tempLastTasks.get(0));
		mainView.setModel(model, tempLastTasks);
	}

	private void initEnterpriseServer() {
		String tempServer = model.getEnterpriseServer();
		try {
			enterpriseUtil.initTaskNames(frame, model, tempServer);
		} catch (Throwable e) {
			handleException(e);
		}
	}

	/**
	 * @param tempFrame
	 * @param tempMainView
	 */
	private void initFrame(String aStartTitle, JComponent tempMainView) {
		JFrame tempFrame = new JFrame(aStartTitle);
		tempFrame.setIconImage(loadImage("MainIcon.gif").getImage());
		if (model.isFrameDecorated()) {
			LOG.info("isFrameDecorated " + model.getFrameBounds());
			tempFrame.setBounds(model.getFrameBounds());
			tempMainView.setBorder(null);
		} else {
			tempFrame.setUndecorated(true);
			Rectangle tempBounds = model.getFrameContentBounds();
			Border tempBorder = BasicBorders.getInternalFrameBorder();
			Insets tempInsets = tempBorder.getBorderInsets(tempFrame);
			tempBounds.x -= tempInsets.left;
			tempBounds.y -= tempInsets.top;
			tempBounds.width += tempInsets.right + tempInsets.left;
			tempBounds.height += tempInsets.bottom + tempInsets.top;
			tempFrame.setBounds(tempBounds);
			tempMainView.setBorder(tempBorder);
			LOG.info("not isFrameDecorated Model=" + model.getFrameBounds() + " Frame=" + tempBounds);
		}
		// Check if frame is visible on Screen
		Dimension tempScreen = Toolkit.getDefaultToolkit().getScreenSize();
		if (model.isFrameDecorated()) {
			// Frame must be visible
			if (tempFrame.getX() > tempScreen.width - 20) {
				tempFrame.setLocation(tempScreen.width - 20, tempFrame.getY());
			}
		} else {
			// Button must be visible
			if (tempFrame.getX() + tempFrame.getWidth() > tempScreen.width - 10) {
				tempFrame.setLocation(tempScreen.width - 10 - tempFrame.getWidth(), tempFrame.getY());
			}
		}
		if (tempFrame.getX() + tempFrame.getWidth() < 5) {
			tempFrame.setLocation(0, tempFrame.getY());
		}
		if (tempFrame.getY() > tempScreen.height - 10) {
			tempFrame.setLocation(tempFrame.getX(), tempScreen.height - 10);
		}
		if (tempFrame.getY() + tempFrame.getHeight() < 5) {
			tempFrame.setLocation(tempFrame.getX(), 0);
		}
		tempFrame.setAlwaysOnTop(model.isAlwaysOnTop());
		tempFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		initFrameListeners(tempFrame);

		tempFrame.getContentPane().setLayout(new CardLayout());
		tempFrame.getContentPane().add(tempMainView, "MainView");
		tempFrame.invalidate();
		tempFrame.setVisible(true);
		if (frame != null) {
			frame.dispose();
		}
		frame = tempFrame;
	}

	private void initFrameListeners(final JFrame aFrame) {
		// aFrame.addWindowListener(new LoggingWindowAdapter());
		aFrame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent aE) {
				super.windowClosing(aE);
				try {
					LOG.info("windowClosing");
					exitApplicationRequestedByUser();
				} catch (Throwable e) {
					handleException(e);
				}
			}

			@Override
			public void windowOpened(WindowEvent aE) {
				super.windowOpened(aE);
				initialized = true;
			}

			@Override
			public void windowIconified(WindowEvent aE) {
				super.windowIconified(aE);
				// When Desktop is shown we get a Windows Iconified.
				Timer tempTimer = new Timer(3000, new ActionListener() {
					public void actionPerformed(ActionEvent aE) {
						// DeIconify now.
						int tempState = aFrame.getExtendedState();
						tempState = tempState & ~Frame.ICONIFIED;
						// aFrame.setExtendedState(tempState);
						EventQueue.invokeLater(new Runnable() {
							public void run() {
								aFrame.setVisible(false);
							}
						});
						EventQueue.invokeLater(new Runnable() {
							public void run() {
								aFrame.setVisible(true);
							}
						});
					}
				});
				tempTimer.setRepeats(false);
				tempTimer.start();
			}
		});
	}

	public void exitApplicationRequestedByUser() {
		repeatingTimer.stop();
		timerRepeats();
		if (taskAcceptTimer != null) {
			LOG.info("exitApplication: commit pending task");
			taskAcceptTimer.stop();
			taskAcceptActionListener.actionPerformed(null);
			timerRepeats();
		}
		try {
			saveApplicationStateToModel(true);
			saveModel();
			taskHistory.updateLastTask(TaskHistory.TASK_CLOSED);
		} catch (Exception e) {
			LOG.exception(e);
		}
		exitApplicationNow();
	}

	private void exitApplicationNow() {
		LOG.info("Start exitThread");
		Thread tempExitThread = new Thread() {
			@Override
			public void run() {
				LOG.info("System.exit(0)");
				System.exit(0);
			}
		};
		tempExitThread.setName(tempExitThread.getName() + "-exitThread");
		tempExitThread.start();
	}

	private void saveApplicationStateToModel(boolean aLogInfo) {
		JFrame tempFrame = getFrame();
		Container tempContentPane = tempFrame.getContentPane();
		Point tempLocationOnScreen = tempContentPane.getLocationOnScreen();
		Dimension tempFrameSize = tempContentPane.getSize();
		Border tempBorder = BasicBorders.getInternalFrameBorder();
		Rectangle tempCurrentLocationOnScreen = new Rectangle(tempLocationOnScreen, tempFrameSize);
		Rectangle tempCurrentFrameBounds = tempFrame.getBounds();
		Insets tempCurrentFrameInsets = tempBorder.getBorderInsets(tempFrame);
		// Save the frame bounds.
		if (tempCurrentLocationOnScreen != null) {
			// Should always be != null
			tempCurrentLocationOnScreen.x += tempCurrentFrameInsets.left;
			tempCurrentLocationOnScreen.y += tempCurrentFrameInsets.top;
			tempCurrentLocationOnScreen.width -= tempCurrentFrameInsets.right + tempCurrentFrameInsets.left;
			tempCurrentLocationOnScreen.height -= tempCurrentFrameInsets.bottom + tempCurrentFrameInsets.top;
			if (model.isFrameDecorated()) {
				if (aLogInfo) {
					LOG.info("isFrameDecorated Frame=" + tempCurrentFrameBounds + " " + tempCurrentLocationOnScreen);
				}
				model.setFrameBounds(getFrame().getBounds());
				model.setFrameContentBounds(tempCurrentLocationOnScreen);
			} else {
				if (aLogInfo) {
					LOG.info("not isFrameDecorated " + tempCurrentLocationOnScreen);
				}
				// see initFrame
				model.setFrameContentBounds(tempCurrentLocationOnScreen);
			}
		}
	}

	public void saveModelNoException() {
		try {
			final Task tempCurrentTask = saveModel();
			if (EventQueue.isDispatchThread()) {
				updateFrame(tempCurrentTask);
			} else {
				EventQueue.invokeLater(new Runnable() {
					public void run() {
						updateFrame(tempCurrentTask);
					}
				});
			}
		} catch (Throwable e) {
			handleException(e);
		}
	}

	private Properties lastProperties;

	private Task saveModel() throws MalformedURLException, FileNotFoundException, IOException {
		File tempFile = getStoreFile();

		Properties tempProperties = model.getProperties();
		if (lastProperties == null || hasChanged(tempProperties, lastProperties)) {
			lastProperties = new Properties();
			savePropertiesSorted(tempFile, tempProperties);
			lastProperties.putAll(tempProperties);
		}

		String tempActualTaskName = model.getCurrentTask();
		Task tempCurrentTask = currentTaskUpdater.updateLastTask(tempActualTaskName);
		return tempCurrentTask;
	}

	private boolean hasChanged(Properties aProperties, Properties aLastProperties) {
		if (aProperties.size() != aLastProperties.size()) {
			return true;
		}
		if (!aProperties.keySet().containsAll(aLastProperties.keySet())) {
			return true;
		}
		if (!aProperties.values().containsAll(aLastProperties.values())) {
			return true;
		}
		return false;
	}

	private void savePropertiesSorted(File aFile, Properties aProperties) throws IOException {
		StringWriter tempWriter = new StringWriter();
		aProperties.store(tempWriter, new Date().toString());
		tempWriter.close();
		BufferedReader tempReader = new BufferedReader(new StringReader(tempWriter.toString()));
		List<String> tempLines = new ArrayList<String>();
		while (tempReader.ready()) {
			String tempLine = tempReader.readLine();
			if (tempLine == null) {
				break;
			}
			tempLines.add(tempLine);
		}
		Collections.sort(tempLines);
		PrintWriter tempFileWriter = new PrintWriter(new FileWriter(aFile));
		for (String tempLine : tempLines) {
			tempFileWriter.println(tempLine);
		}
		tempFileWriter.close();
	}

	private static final Color[] COLORS = new Color[] { Color.RED, Color.BLUE, Color.CYAN, Color.MAGENTA,
			Color.DARK_GRAY, Color.WHITE, Color.GREEN, Color.GRAY };

	private void updateFrame(Task aCurrentTask) {
		if (aCurrentTask == null) {
			return;
		}
		String tempName = aCurrentTask.getName();
		frame.setTitle(formatTitleTime(aCurrentTask) + " " + tempName);
		BufferedImage tempBufferedImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_BGR);
		Graphics g = tempBufferedImage.getGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 64, 64);

		String tempChars;
		StringTokenizer tempTasks = new StringTokenizer(tempName, " -");
		int tempCountTokens = tempTasks.countTokens();
		tempChars = "";
		if (tempCountTokens == 1) {
			tempChars = tempName;
		} else if (tempCountTokens == 2) {
			tempChars += (tempTasks.nextToken() + " ").substring(0, 2).trim();
			tempChars += (tempTasks.nextToken() + " ").substring(0, 2).trim();
		} else if (tempCountTokens == 3) {
			tempChars += (tempTasks.nextToken() + " ").substring(0, 1).trim();
			tempChars += (tempTasks.nextToken() + " ").substring(0, 1).trim();
			tempChars += (tempTasks.nextToken() + " ").substring(0, 2).trim();
		} else {
			while (tempChars.length() < 4 && tempTasks.hasMoreTokens()) {
				tempChars += tempTasks.nextToken().charAt(0);
			}
		}
		// System.out.println(tempName + " -> " + tempChars);
		int x = 0;
		int y = 0;
		for (int i = 0; i < tempChars.length() && y < 64; i++) {
			int c = tempChars.charAt(i);
			g.setColor(COLORS[c % COLORS.length]);
			g.fillRect(x, y, 32, 32);
			x += 32;
			if (x >= 64) {
				x = 0;
				y += 32;
			}
		}
		g.setColor(Color.YELLOW);
		g.fillOval(16, 16, 32, 32);
		g.setColor(Color.BLACK);
		g.fillOval(29, 29, 6, 6);
		g.dispose();
		frame.setIconImage(tempBufferedImage);
	}

	private static final DateFormat TIME_HOUR_FORMAT = new SimpleDateFormat("HH:mm");
	private static final DateFormat TIME_MINUTE_FORMAT = new SimpleDateFormat("mm:ss");

	private String formatTitleTime(Task aCurrentTask) {
		Calendar tempCal = Calendar.getInstance();
		tempCal.clear();
		tempCal.set(Calendar.HOUR_OF_DAY, 0);
		tempCal.set(Calendar.MINUTE, 0);
		tempCal.set(Calendar.SECOND, 0);
		tempCal.add(Calendar.MILLISECOND, (int) aCurrentTask.getMillis());
		if (tempCal.get(Calendar.HOUR_OF_DAY) > 0) {
			return TIME_HOUR_FORMAT.format(tempCal.getTime());
		}
		return TIME_MINUTE_FORMAT.format(tempCal.getTime());
	}

	public void loadModel() {
		MainModel tempMainModel = new MainModel();
		Properties tempProperties = new Properties();
		try {
			// We are local
			File tempFile = getStoreFile();
			new BackupFile().backupFile(tempFile, 10);
			if (tempFile.exists()) {
				FileInputStream tempFileInputStream = new FileInputStream(tempFile);
				tempProperties.load(tempFileInputStream);
				tempFileInputStream.close();
			}
		} catch (Throwable e) {
			handleException(e);
		}
		tempMainModel.setProperties(tempProperties);
		model = tempMainModel;
	}

	/**
	 * @return
	 */
	private File getStoreFile() {
		return new File(FileUtil.getDataFolder() + "/ptc.properties");
	}

	public void toggleFrameDecorator() {
		if (model.isFrameDecorated()) {
			model.setFrameBounds(getFrame().getBounds());
			model.setFrameContentBounds(new Rectangle(getFrame().getLocationOnScreen(), getFrame().getContentPane()
					.getSize()));
		}
		boolean tempNewIsFrameDecorated = !model.isFrameDecorated();
		model.setFrameDecorated(tempNewIsFrameDecorated);

		getFrame().setVisible(false);
		initFrame(getFrame().getTitle(), (JComponent) frame.getContentPane().getComponent(0));
	}

	/**
	 * @return Returns the frame.
	 */
	public JFrame getFrame() {
		return frame;
	}

	/**
	 * @param aFrame
	 *            The frame to set.
	 */
	public void setFrame(JFrame aFrame) {
		frame = aFrame;
	}

	private Timer taskAcceptTimer;
	private ActionListener taskAcceptActionListener;
	private boolean inTaskAccepting = false;

	public void otherTaskName(final String aString) {
		if (inTaskAccepting) {
			LOG.info("Ignore because inTaskAccepting: " + aString);
			return;
		}
		if (aString.trim().length() == 0) {
			LOG.info("Ignore empty String inTaskAccepting: '" + aString + "'");
			return;
		}
		LOG.info("Other Task requested: '" + aString + "'");
		if (taskAcceptTimer != null) {
			taskAcceptTimer.stop();
		}
		taskAcceptActionListener = new ActionListener() {
			public void actionPerformed(ActionEvent aE) {
				acceptNewTask(aString);
			}
		};
		Timer tempTimer = new Timer(30000, taskAcceptActionListener);
		mainView.newTaskPending();
		tempTimer.setRepeats(false);
		tempTimer.start();
		taskAcceptTimer = tempTimer;
	}

	private TaskHistory taskHistory = new TaskHistory();

	private TaskUpdater currentTaskUpdater = taskHistory;

	private EnterpriseUtilRemote enterpriseUtil = new EnterpriseUtil();

	private void timerRepeats() {
		if (shutdownPending) {
			LOG.info("Do not timerRepeats as shutdownPending");
			return;
		}
		saveApplicationStateToModel(false);
		if (taskAcceptTimer == null) {
			// Suspend save until new task is "commited"
			saveModelNoException();
		}
	}

	private void timerNewTask(String aNewTaskName) {
		model.setCurrentTask(aNewTaskName);
		saveModelNoException();
		taskAcceptTimer = null;
		taskAcceptActionListener = null;
	}

	private ImageIcon loadImage(String aString) {
		URL tempURL = getClass().getResource("/" + aString);
		return new ImageIcon(tempURL);
	}

	public void bringToFront() {
		int tempState = frame.getExtendedState();
		tempState = tempState & ~Frame.ICONIFIED;
		frame.setExtendedState(tempState);
	}

	public boolean isInitialized() {
		return initialized;
	}

	public void showLastWeek() {
		try {
			Calendar tempCal = Calendar.getInstance();
			tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
			tempCal.set(Calendar.HOUR_OF_DAY, 0);
			tempCal.set(Calendar.MINUTE, 0);
			tempCal.set(Calendar.SECOND, 0);
			tempCal.set(Calendar.MILLISECOND, 0);
			long tempTo = tempCal.getTimeInMillis();
			tempCal.add(Calendar.DAY_OF_YEAR, -7);
			long tempFrom = tempCal.getTimeInMillis();
			TaskReport tempTaskReport = new TaskReport(taskHistory, frame, model.getTaskDelimiter(), model
					.getDontSumChar(), enterpriseUtil.getFixedTaskNames());
			GroupBy[] tempGroupBy = new GroupBy[] { GroupByList.getGroupBy(GroupByList.DAY),
					GroupByList.getGroupBy(GroupByList.NONE) };
			List<Action> tempActions = createAdditionalActions(tempTo, tempFrom);
			tempTaskReport.showReport(tempFrom, tempTo, tempGroupBy, model.getTimeFormat(), tempActions);
		} catch (Throwable e) {
			handleException(e);
		}
	}

	private List<Action> createAdditionalActions(long tempTo, long tempFrom) {
		List<Action> tempActions = new ArrayList<Action>();
		Action tempSaveReportAction = enterpriseUtil.createShowReportAction(this, tempFrom, tempTo);
		tempActions.add(tempSaveReportAction);
		Action tempShowBookingSystemAction = enterpriseUtil.createShowBookingSystemAction(this);
		tempActions.add(tempShowBookingSystemAction);
		return tempActions;
	}

	private void handleException(Throwable aE) {
		LOG.exception(aE);
		DisplayHelper.displayException(frame, aE);
	}

	public void about() {
		DisplayHelper.displayAbout(frame);
	}

	public void showThisWeek() {
		try {
			Calendar tempCal = Calendar.getInstance();
			tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
			tempCal.set(Calendar.HOUR_OF_DAY, 0);
			tempCal.set(Calendar.MINUTE, 0);
			tempCal.set(Calendar.SECOND, 0);
			tempCal.set(Calendar.MILLISECOND, 0);
			long tempFrom = tempCal.getTimeInMillis();
			tempCal.add(Calendar.DAY_OF_YEAR, +7);
			long tempTo = tempCal.getTimeInMillis();
			TaskReport tempTaskReport = new TaskReport(taskHistory, frame, model.getTaskDelimiter(), model
					.getDontSumChar(), enterpriseUtil.getFixedTaskNames());
			GroupBy[] tempGroupBy = new GroupBy[] { GroupByList.getGroupBy(GroupByList.DAY),
					GroupByList.getGroupBy(GroupByList.NONE) };
			List<Action> tempActions = createAdditionalActions(tempTo, tempFrom);
			tempTaskReport.showReport(tempFrom, tempTo, tempGroupBy, model.getTimeFormat(), tempActions);
		} catch (Throwable e) {
			handleException(e);
		}
	}

	public void showCustomReport() {
		try {
			ReportSelection tempReportSelection = new ReportSelection();
			tempReportSelection.setGroupBy(model.getGroupBy());
			tempReportSelection.setTimeFormat(model.getTimeFormat());
			boolean tempOk = DisplayHelper.displayComponent(frame, "Select Report...", tempReportSelection);
			if (tempOk) {
				TaskReport tempTaskReport = new TaskReport(taskHistory, frame, model.getTaskDelimiter(), model
						.getDontSumChar(), enterpriseUtil.getFixedTaskNames());
				long tempFrom = tempReportSelection.getFrom();
				long tempTo = tempReportSelection.getTo();
				List<Action> tempActions = createAdditionalActions(tempTo, tempFrom);
				tempTaskReport.showReport(tempFrom, tempTo, tempReportSelection.getGroupBys(), tempReportSelection
						.getTimeFormat(), tempActions);
			}
		} catch (Throwable e) {
			handleException(e);
		}
	}

	public void options() {
		PreferencesSelection tempPreferencesSelection = new PreferencesSelection();
		tempPreferencesSelection.setValues(model.getProperties());
		boolean tempOk = DisplayHelper.displayComponent(frame, "Select Preferences...", tempPreferencesSelection);
		if (tempOk) {
			model.getProperties().putAll(tempPreferencesSelection.getValues());
			saveModelNoException();
			initEnterpriseServer();
			try {
				setMainViewModel();
			} catch (IOException e) {
				handleException(e);
			}
			frame.setAlwaysOnTop(model.isAlwaysOnTop());
		}
	}

	public void editTasks() {
		if (taskAcceptTimer != null) {
			LOG.info("editTask: commit pending task");
			taskAcceptTimer.stop();
			taskAcceptTimer.setInitialDelay(0);
			taskAcceptTimer.start();
			waitForTaskTimerAndEditTasks();
		} else {
			editTasksNow();
		}
	}

	private void waitForTaskTimerAndEditTasks() {
		if (taskAcceptTimer == null) {
			editTasksNow();
		} else {
			EventQueue.invokeLater(new Runnable() {
				public void run() {
					waitForTaskTimerAndEditTasks();
				}
			});
		}
	}

	private void editTasksNow() {
		saveModelNoException();
		TaskEditor tempTaskEdtior = new TaskEditor();
		long tempStartPos;
		List<PosAndContent<Task>> tempTasks;
		try {
			tempTasks = taskHistory.getLastLinesForEdit();
			tempStartPos = tempTasks.get(0).getPosInFile();
			tempTaskEdtior.setTasks(tempTasks, enterpriseUtil);
		} catch (Exception e) {
			handleException(e);
			return;
		}
		TaskUpdater tempOldTaskUpdater = currentTaskUpdater;
		frame.setAlwaysOnTop(false);
		try {
			if (DisplayHelper.displayComponent(frame, "Edit Tasks", tempTaskEdtior)) {
				for (Iterator<PosAndContent<Task>> i = tempTasks.iterator(); i.hasNext();) {
					PosAndContent<Task> tempPosAndContent = i.next();
					if (tempTaskEdtior.getDeletedTasks().contains(tempPosAndContent)) {
						i.remove();
					}
				}
				try {
					taskHistory.saveTasks(tempStartPos, tempTasks);
				} catch (IOException e) {
					handleException(e);
				}
			}
		} finally {
			currentTaskUpdater = tempOldTaskUpdater;
			frame.setAlwaysOnTop(model.isAlwaysOnTop());
		}
	}

	public List<String> getFixedTaskNames() {
		return enterpriseUtil.getFixedTaskNames();
	}

	/**
	 * @return the enterpriseUtil
	 */
	public EnterpriseUtilRemote getEnterpriseUtil() {
		return enterpriseUtil;
	}

	/**
	 * @return the taskHistory
	 */
	public TaskHistory getTaskHistory() {
		return taskHistory;
	}

	private void acceptNewTask(final String aString) {
		if (shutdownPending) {
			LOG.info("Do not acceptNewTask "+aString+" as shutdownPending");
			return;
		}
		inTaskAccepting = true;
		try {
			timerNewTask(aString);
			mainView.newTaskAccepted(aString);
			LOG.info("newTaskAccepted: " + aString);
		} finally {
			inTaskAccepting = false;
		}
	}

	public void setEnterpriseUtil(EnterpriseUtilRemote aEnterpriseUtil) {
		enterpriseUtil = aEnterpriseUtil;
	}
}