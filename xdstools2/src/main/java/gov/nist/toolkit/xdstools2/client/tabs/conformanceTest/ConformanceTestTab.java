package gov.nist.toolkit.xdstools2.client.tabs.conformanceTest;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.TabBar;
import gov.nist.toolkit.actortransaction.client.ActorType;
import gov.nist.toolkit.interactiondiagram.client.events.DiagramClickedEvent;
import gov.nist.toolkit.interactiondiagram.client.events.DiagramPartClickedEventHandler;
import gov.nist.toolkit.interactiondiagram.client.widgets.InteractionDiagram;
import gov.nist.toolkit.results.client.TestInstance;
import gov.nist.toolkit.services.client.AbstractOrchestrationResponse;
import gov.nist.toolkit.services.client.RepOrchestrationResponse;
import gov.nist.toolkit.session.client.logtypes.TestOverviewDTO;
import gov.nist.toolkit.session.client.sort.TestSorter;
import gov.nist.toolkit.sitemanagement.client.SiteSpec;
import gov.nist.toolkit.testkitutilities.client.SectionDefinitionDAO;
import gov.nist.toolkit.testkitutilities.client.TestCollectionDefinitionDAO;
import gov.nist.toolkit.xdstools2.client.command.command.AutoInitConformanceTestingCommand;
import gov.nist.toolkit.xdstools2.client.command.command.GetTestsOverviewCommand;
import gov.nist.toolkit.xdstools2.client.widgets.PopupMessage;
import gov.nist.toolkit.xdstools2.client.ToolWindow;
import gov.nist.toolkit.xdstools2.client.Xdstools2;
import gov.nist.toolkit.xdstools2.client.event.testSession.TestSessionChangedEvent;
import gov.nist.toolkit.xdstools2.client.event.testSession.TestSessionChangedEventHandler;
import gov.nist.toolkit.xdstools2.client.tabs.GatewayTestsTabs.BuildIGTestOrchestrationButton;
import gov.nist.toolkit.xdstools2.client.widgets.LaunchInspectorClickHandler;
import gov.nist.toolkit.xdstools2.client.widgets.buttons.AbstractOrchestrationButton;
import gov.nist.toolkit.xdstools2.shared.command.request.GetTestsOverviewRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All Conformance tests will be run out of here
 */
public class ConformanceTestTab extends ToolWindow implements TestRunner, TestsHeaderView.Controller {

	private final ConformanceTestTab me;

	private TestsHeaderView testsHeaderView = new TestsHeaderView(this);
	private TestContextView testContextView;

	private TestDisplayGroup testDisplayGroup;
	private TestContext testContext = new TestContext(this);

	private AbstractOrchestrationResponse orchestrationResponse;
	private RepOrchestrationResponse repOrchestrationResponse;

	private final TestStatistics testStatistics = new TestStatistics();

	private ActorOption currentActorOption = new ActorOption("none");
	private String currentActorTypeDescription;
	private SiteSpec siteToIssueTestAgainst = null;

   // stuff that needs delayed setting when launched via activity
   private String initTestSession = null;

	// Descriptions of current test list
	private List<TestCollectionDefinitionDAO> testCollectionDefinitionDAOs;

	// Test results
	// testname ==> results
	private Map<String, TestOverviewDTO> testOverviewDTOs = new HashMap<>();

	// for each actor type id, the list of tests for it
	private Map<String, List<TestInstance>> testsPerActor = new HashMap<>();

	private ConformanceTestMainView mainView;
	private AbstractOrchestrationButton orchInit = null;


	public ConformanceTestTab() {
		me = this;
		mainView = new ConformanceTestMainView(this, new OptionsTabBar());
		testContextView = new TestContextView(this, mainView.getTestSessionDescription(), testContext);
		testContext.setTestContextView(testContextView);
		testDisplayGroup = new TestDisplayGroup(testContext, testContextView, this);
	}

	@Override
	public void onTabLoad(boolean select, String eventName) {
		testContextView.updateTestingContextDisplay();
		mainView.getTestSessionDescription().addClickHandler(testContextView);

		addEast(mainView.getTestSessionDescriptionPanel());
		registerTab(select, eventName);

		tabTopPanel.add(mainView.getToolPanel());

      // Reload if the test session changes
      Xdstools2.getEventBus().addHandler(TestSessionChangedEvent.TYPE, new TestSessionChangedEventHandler() {
         @Override
         public void onTestSessionChanged(TestSessionChangedEvent event) {
            if (event.getChangeType() == TestSessionChangedEvent.ChangeType.SELECT) {
               loadTestCollections();
            }
         }
      });

		mainView.getActorTabBar().addSelectionHandler(new ActorSelectionHandler());
		mainView.getOptionsTabBar().addSelectionHandler(new OptionSelectionHandler());

      // Initial load of tests in a test session
      loadTestCollections();

		// Register the Diagram clicked event handler
		Xdstools2.getEventBus().addHandler(DiagramClickedEvent.TYPE, new DiagramPartClickedEventHandler() {
			@Override
			public void onClicked(TestInstance testInstance, InteractionDiagram.DiagramPart part) {
				if (InteractionDiagram.DiagramPart.RequestConnector.equals(part)
						|| InteractionDiagram.DiagramPart.ResponseConnector.equals(part)) {
					new LaunchInspectorClickHandler(testInstance, getCurrentTestSession(), new SiteSpec(testContext.getSiteName())).onClick(null);
//					launchInspectorTab(testInstance, getCurrentTestSession());
				}
			}
		});
	}

	private boolean allowRun() {
		ActorAndOption aao = ActorOptionManager.actorDetails(currentActorOption);
		boolean selfTest = false;
		if (orchInit != null) {
			selfTest = orchInit.isSelfTest();
		}
		boolean externalStart = aao != null && aao.isExternalStart();
		return !externalStart || selfTest;
	}

	private boolean showValidate() {
		ActorAndOption aao = ActorOptionManager.actorDetails(currentActorOption);
		boolean selfTest = isSelfTest();
		boolean externalStart = aao != null && aao.isExternalStart();
		return externalStart && !selfTest;
	}

	private boolean isSelfTest() {
		if (orchInit != null) {
			return orchInit.isSelfTest();
		}
		return false;
	}

	public void removeTestDetails(TestInstance testInstance) {
		testOverviewDTOs.remove(testInstance.getId());
		updateTestsOverviewHeader();
	}

	private void addTestOverview(TestOverviewDTO dto) {
		testOverviewDTOs.put(dto.getName(), dto);
	}

	private void removeTestOverview(TestOverviewDTO dto) {
		testOverviewDTOs.remove(dto.getName());
	}

	/**
	 * currentActorTypeDescription is initialized late so calling this when it is available
	 * updates the build since it could not be constructed correctly at first.
	 * This must be called after testCollectionDefinitionDAOs is initialized.
	 */
	private void updateTestsOverviewHeader() {

		// Build statistics
		testStatistics.clear();
		if (testOverviewDTOs != null) {
			for (TestOverviewDTO testOverview : testOverviewDTOs.values()) {
				if (testOverview.isRun()) {
					if (testOverview.isPass()) {
						testStatistics.addSuccessful();
					} else {
						testStatistics.addWithError();
					}
				}
			}
		}

		// Display testStatus with statistics
		testsHeaderView.allowRun(allowRun());
		testsHeaderView.update(testStatistics, currentActorTypeDescription + " - " + getCurrentOptionTitle());
	}

	private String getCurrentOptionTitle() {
		ActorAndOption aao =  ActorOptionManager.actorDetails(currentActorOption);
		if (aao != null) {
			if (aao.getOptionId().equals(currentActorOption.optionId))
				return aao.getOptionTitle();
		}
		return "Required";
	}

	@Override
	public String getTitle() { return "Conformance Tests"; }

	/**
	 *
	 */
	private void initializeTestingContext() {
		if (initTestSession != null) {
			setCurrentTestSession(initTestSession);
			initTestSession = null;
		}
		if (getCurrentTestSession() == null || getCurrentTestSession().equals("")) {
			testContextView.updateTestingContextDisplay();
			return;
		}
		getToolkitServices().getAssignedSiteForTestSession(getCurrentTestSession(), new AsyncCallback<String>() {
			@Override
			public void onFailure(Throwable throwable) {
				new PopupMessage("getAssignedSiteForTestSession failed: " + throwable.getMessage());
			}

			@Override
			public void onSuccess(String s) {
				testContext.setSiteName(s);
				testContextView.updateTestingContextDisplay();
			}
		});
	}

	// actor type selection changes
	private class ActorSelectionHandler implements SelectionHandler<Integer> {

		@Override
		public void onSelection(SelectionEvent<Integer> selectionEvent) {
			int i = selectionEvent.getSelectedItem();
			String newActorTypeId = testCollectionDefinitionDAOs.get(i).getCollectionID();
			if (!newActorTypeId.equals(currentActorOption.actorTypeId)) {
				orchestrationResponse = null;  // so we know orchestration not set up
				currentActorOption = new ActorOption(newActorTypeId);
				mainView.getOptionsTabBar().display(newActorTypeId);
			}
		}
	}

	private class OptionSelectionHandler implements SelectionHandler<Integer> {

		@Override
		public void onSelection(SelectionEvent<Integer> selectionEvent) {
			int i = selectionEvent.getSelectedItem();
			List<String> optionIds = ActorOptionManager.optionIds(currentActorOption.actorTypeId);
			if (i < optionIds.size()) {
				currentActorOption.setOptionId(optionIds.get(i));
			}
			changeDisplayedActorAndOptionType(currentActorOption);
		}
	}

	// for use by ConfActorActivity
	public void changeDisplayedActorAndOptionType(ActorOption actorOption) {
		currentActorOption = actorOption;
		currentActorTypeDescription = getDescriptionForTestCollection(currentActorOption.actorTypeId);
		displayTestingPanel(mainView.getTestsPanel());
	}

   private String getDescriptionForTestCollection(String collectionId) {
      if (testCollectionDefinitionDAOs == null) return "not initialized";
      for (TestCollectionDefinitionDAO dao : testCollectionDefinitionDAOs) {
         if (dao.getCollectionID().equals(collectionId)) { return dao.getCollectionTitle(); }
      }
      return "???";
   }

	// load tab bar with actor types
	private void loadTestCollections() {
		// TabBar listing actor types
		getToolkitServices().getTestCollections("actorcollections", new AsyncCallback<List<TestCollectionDefinitionDAO>>() {
			@Override
			public void onFailure(Throwable throwable) { new PopupMessage("getTestCollections: " + throwable.getMessage()); }

			@Override
			public void onSuccess(List<TestCollectionDefinitionDAO> testCollectionDefinitionDAOs) {
				me.testCollectionDefinitionDAOs = testCollectionDefinitionDAOs;
				displayActorsTabBar(mainView.getActorTabBar());
				currentActorTypeDescription = getDescriptionForTestCollection(currentActorOption.actorTypeId);
				updateTestsOverviewHeader();

				// This is a little wierd being here. This depends on initTestSession
				// which is set AFTER onTabLoad is run so run here - later in the initialization
				// initTestSession is set from ConfActorActivity
				initializeTestingContext();
			}
		});
	}

   private HTML loadingMessage;

	private class RefreshTestCollectionHandler implements ClickHandler {

		@Override
		public void onClick(ClickEvent clickEvent) {
			initializeTestDisplay(mainView.getTestsPanel());
			displayTestCollection(mainView.getTestsPanel());
		}
	}

	// This includes Testing Environment and initialization sections
	private void displayTestingPanel(final Panel testsPanel) {

		mainView.getInitializationPanel().clear();
		testStatistics.clear();
		displayOrchestrationHeader(mainView.getInitializationPanel());

		initializeTestDisplay(testsPanel);
		displayTestCollection(testsPanel);
	}

	private void initializeTestDisplay(Panel testsPanel) {
		testDisplayGroup.clear();  // so they reload
		testsPanel.clear();

		loadingMessage = new HTML("Initializing...");
		loadingMessage.setStyleName("loadingMessage");
		testsPanel.add(loadingMessage);
		testsHeaderView.showSelfTestWarning(isSelfTest());
	}

	// load test results for a single test collection (actor/option) for a single site
	private void displayTestCollection(final Panel testsPanel) {


		// what tests are in the collection
		currentActorOption.loadTests(new AsyncCallback<List<TestInstance>>() {
			@Override
			public void onFailure(Throwable throwable) {
				new PopupMessage("getTestlogListing: " + throwable.getMessage());
			}

			@Override
			public void onSuccess(List<TestInstance> testInstances) {
				testStatistics.clear();
				testStatistics.setTestCount(testInstances.size());
				loadingMessage.setHTML("Loading...");
				displayTests(testsPanel, testInstances, allowRun());
			}
		});
	}


	private void displayTests(final Panel testsPanel, List<TestInstance> testInstances, boolean allowRun) {
        // results (including logs) for a collection of tests

        testDisplayGroup.allowRun(allowRun);
        testDisplayGroup.showValidate(showValidate());
        new GetTestsOverviewCommand() {
            @Override
            public void onComplete(List<TestOverviewDTO> testOverviews) {
                // sort tests by dependencies and alphabetically
                // save in testsPerActor so they run in this order as well
                List<TestInstance> testInstances1 = new ArrayList<>();
                testOverviews = new TestSorter().sort(testOverviews);
                for (TestOverviewDTO dto : testOverviews) {
                    testInstances1.add(dto.getTestInstance());
                }
                testsPerActor.put(currentActorOption.actorTypeId, testInstances1);

                testsPanel.clear();
                testsHeaderView.allowRun(allowRun());
                testsPanel.add(testsHeaderView.asWidget());
                testStatistics.clear();
                testStatistics.setTestCount(testOverviews.size());
                for (TestOverviewDTO testOverview : testOverviews) {
                    addTestOverview(testOverview);
                    TestDisplay testDisplay = testDisplayGroup.display(testOverview);
                    testsPanel.add(testDisplay.asWidget());
                }
                updateTestsOverviewHeader();

                new AutoInitConformanceTestingCommand(){
                    @Override
                    public void onComplete(Boolean result) {
                        if (result)
                            orchInit.handleClick(null);   // auto init orchestration
                    }
                }.run(getCommandContext());
            }
        }.run(new GetTestsOverviewRequest(getCommandContext(), testInstances));
    }

	private void displayOrchestrationHeader(Panel initializationPanel) {
		String label = "Initialize Test Environment";
		if (currentActorOption.isRep()) {
			orchInit = new BuildRepTestOrchestrationButton(this, testContext, testContextView, initializationPanel, label);
			orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
			initializationPanel.add(orchInit.panel());
		}
		else if (currentActorOption.isReg()) {
			orchInit = new BuildRegTestOrchestrationButton(this, testContext, testContextView, initializationPanel, label);
			orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
			initializationPanel.add(orchInit.panel());
		}
		else if (currentActorOption.isRg()) {
			orchInit = new BuildRgTestOrchestrationButton(this, initializationPanel, label, testContext, testContextView, this);
			orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
			initializationPanel.add(orchInit.panel());
		}
		else if (currentActorOption.isIg()) {
			orchInit = new BuildIGTestOrchestrationButton(this, initializationPanel, label, testContext, testContextView, this, false, currentActorOption);
			orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
			initializationPanel.add(orchInit.panel());
		}
		else if (currentActorOption.isInitiatingImagingGatewaySut()) {
		   orchInit = new BuildIIGTestOrchestrationButton(this, testContext, testContextView, initializationPanel, label);
         orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
         initializationPanel.add(orchInit.panel());
		}
		else if (currentActorOption.isRespondingingImagingGatewaySut()) {
         orchInit = new BuildRIGTestOrchestrationButton(this, testContext, testContextView, initializationPanel, label);
         orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
         initializationPanel.add(orchInit.panel());
		} 
		else if (currentActorOption.isImagingDocSourceSut()) {
         orchInit = new BuildIDSTestOrchestrationButton(this, testContext, testContextView, initializationPanel, label);
         orchInit.addSelfTestClickHandler(new RefreshTestCollectionHandler());
         initializationPanel.add(orchInit.panel());
		}
		else if (currentActorOption.isEdgeServerSut()) {
		   // TODO not implemented yet.
		}
		else {
			if (testContext.getSiteUnderTest() != null)
				siteToIssueTestAgainst = testContext.getSiteUnderTestAsSiteSpec();
		}

   }


	private void displayActorsTabBar(TabBar actorTabBar) {
		if (actorTabBar.getTabCount() == 0) {
			for (TestCollectionDefinitionDAO def : testCollectionDefinitionDAOs) {
				actorTabBar.addTab(def.getCollectionTitle());
			}
		}
	}

	@Override
	public RunAllTestsClickHandler getRunAllClickHandler() {
		return new RunAllTestsClickHandler(currentActorOption.actorTypeId);
	}

	private class RunAllTestsClickHandler implements ClickHandler, TestDone {
		String actorTypeId;
		List<TestInstance> tests = new ArrayList<TestInstance>();

		RunAllTestsClickHandler(String actorTypeId ) {
			this.actorTypeId = actorTypeId;
		}

      @Override
      public void onClick(ClickEvent clickEvent) {
         clickEvent.preventDefault();
         clickEvent.stopPropagation();

         for (TestInstance testInstance : testsPerActor.get(actorTypeId))
            tests.add(testInstance);
         onDone(null);
      }

      @Override
      public void onDone(TestInstance unused) {
         testsHeaderView.showRunningMessage(true);
         if (tests.size() == 0) {
            testsHeaderView.showRunningMessage(false);
            return;
         }
         TestInstance next = tests.get(0);
         tests.remove(0);
         runTest(next, this);
      }
   }

	private class RunAllSectionsClickHandler implements ClickHandler, TestDone {
		TestInstance testInstance;
		List<TestInstance> sections = new ArrayList<TestInstance>();  // One TestInstance per section

		RunAllSectionsClickHandler(TestInstance testInstance) { this.testInstance = testInstance; }

		@Override
		public void onClick(ClickEvent clickEvent) {
			clickEvent.preventDefault();
			clickEvent.stopPropagation();

			getToolkitServices().getTestSectionsDAOs(getCurrentTestSession(), testInstance, new AsyncCallback<List<SectionDefinitionDAO>>() {
				@Override
				public void onFailure(Throwable throwable) { new PopupMessage(throwable.getMessage()); }

				@Override
				public void onSuccess(List<SectionDefinitionDAO> sectionDefinitionDAOs) {
					sections.clear();
					for (SectionDefinitionDAO dao : sectionDefinitionDAOs) {
						TestInstance ti = testInstance.copy();
						ti.setSection(dao.getSectionName());
						ti.setSutInitiated(dao.isSutInitiated());
					}
					onDone(null);
				}
			});
		}

		@Override
		public void onDone(TestInstance unused) {
			testsHeaderView.showRunningMessage(true);
			if (sections.size() == 0) {
				testsHeaderView.showRunningMessage(false);
				return;
			}
			TestInstance next = sections.get(0);
			sections.remove(0);
			runSection(next, this);
		}
	}

	@Override
	public DeleteAllClickHandler getDeleteAllClickHandler() {
		return new DeleteAllClickHandler(currentActorOption.actorTypeId);
	}

	@Override
	public ClickHandler getRefreshTestCollectionClickHandler() {
		return new RefreshTestCollectionHandler();
	}

   private class DeleteAllClickHandler implements ClickHandler {
      String actorTypeId;

      DeleteAllClickHandler(String actorTypeId) {
         this.actorTypeId = actorTypeId;
      }

      @Override
      public void onClick(ClickEvent clickEvent) {
         clickEvent.preventDefault();
         clickEvent.stopPropagation();
         List <TestInstance> tests = testsPerActor.get(actorTypeId);
         for (TestInstance testInstance : tests) {
            getToolkitServices().deleteSingleTestResult(getCurrentTestSession(), testInstance,
               new AsyncCallback <TestOverviewDTO>() {
                  @Override
                  public void onFailure(Throwable throwable) {
                     new PopupMessage(throwable.getMessage());
                  }

					@Override
					public void onSuccess(TestOverviewDTO testOverviewDTO) {
						testDisplayGroup.display(testOverviewDTO);
//						displayTest(testsPanel, testDisplayGroup, testOverviewDTO);
						removeTestOverview(testOverviewDTO);
						updateTestsOverviewHeader();
					}
				});
			}
		}
	}

	ClickHandler getInspectClickHandler(TestInstance testInstance) {
		return new LaunchInspectorClickHandler(testInstance, getCurrentTestSession(), new SiteSpec(testContext.getSiteName()));
	}

	/**
	 * if testInstance contains a sectionName then run that section, otherwise run entire test.
	 * TestInstance.sutInitiated indicates whether one or more sections have to be initiated by the SUT
	 * @param sectionInstance
	 * @param sectionDone
	 */
	public void runSection(final TestInstance sectionInstance, final TestDone sectionDone) {
		Map<String, String> parms = initializeTestParameters();

		if (parms == null) return;

		try {
			getToolkitServices().runTest(getEnvironmentSelection(), getCurrentTestSession(), getSiteToIssueTestAgainst(), sectionInstance, parms, true, new AsyncCallback<TestOverviewDTO>() {
				@Override
				public void onFailure(Throwable throwable) {
					new PopupMessage(throwable.getMessage());
				}

				@Override
				public void onSuccess(TestOverviewDTO testOverviewDTO) {
					// returned testStatus of entire test
					testDisplayGroup.display(testOverviewDTO);
					addTestOverview(testOverviewDTO);
					updateTestsOverviewHeader();
					// Schedule next section to be run
					if (sectionDone != null)
						sectionDone.onDone(sectionInstance);
				}
			});
		} catch (Exception e) {
			new PopupMessage(e.getMessage());
		}
	}




	public void runTest(final TestInstance testInstance, final TestDone testDone) {
		Map<String, String> parms = initializeTestParameters();

		if (parms == null) return;

		try {
			getToolkitServices().runTest(getEnvironmentSelection(), getCurrentTestSession(), getSiteToIssueTestAgainst(), testInstance, parms, true, new AsyncCallback<TestOverviewDTO>() {
				@Override
				public void onFailure(Throwable throwable) {
					new PopupMessage(throwable.getMessage());
				}

				@Override
				public void onSuccess(TestOverviewDTO testOverviewDTO) {
					// returned testStatus of entire test
					testDisplayGroup.display(testOverviewDTO);
					addTestOverview(testOverviewDTO);
					updateTestsOverviewHeader();
					// Schedule next test to be run
					if (testDone != null)
						testDone.onDone(testInstance);
				}
			});
		} catch (Exception e) {
			new PopupMessage(e.getMessage());
		}

   }

	private Map<String, String> initializeTestParameters() {
		Map<String, String> parms = new HashMap<>();

		if (orchestrationResponse == null) {
			new PopupMessage("Initialize Test Environment before running tests.");
			return null;
		}

		if (ActorType.REPOSITORY.getShortName().equals(currentActorOption.actorTypeId)) {
			parms.put("$patientid$", repOrchestrationResponse.getPid().asString());
		}

		if (getSiteToIssueTestAgainst() == null) {
			new PopupMessage("Test Setup must be initialized");
			return null;
		}
		return parms;
	}

	class SelfTestValueChangeHandler implements ValueChangeHandler<Boolean> {

		@Override
		public void onValueChange(ValueChangeEvent<Boolean> valueChangeEvent) {

		}
	}

	public void setRepOrchestrationResponse(RepOrchestrationResponse repOrchestrationResponse) {
		this.repOrchestrationResponse = repOrchestrationResponse;
		setOrchestrationResponse(repOrchestrationResponse);
	}

	public void setOrchestrationResponse(AbstractOrchestrationResponse repOrchestrationResponse) {
		this.orchestrationResponse = repOrchestrationResponse;
	}

	public String getWindowShortName() {
		return "testloglisting";
	}

	private SiteSpec getSiteToIssueTestAgainst() {
		return siteToIssueTestAgainst;
	}

	public void setSiteToIssueTestAgainst(SiteSpec siteToIssueTestAgainst) {
		this.siteToIssueTestAgainst = siteToIssueTestAgainst;
	}

   public void setInitTestSession(String initTestSession) {
      this.initTestSession = initTestSession;
   }

    public TestContext getTestContext() {
        return testContext;
    }
}