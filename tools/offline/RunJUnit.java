import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestIdentifier;
public class RunJUnit {
  public static void main(String[] a) {
    LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectPackage("com.itemcasino")).build();
    Launcher l = LauncherFactory.create();
    SummaryGeneratingListener sum = new SummaryGeneratingListener();
    TestExecutionListener print = new TestExecutionListener() {
      public void executionFinished(TestIdentifier id, TestExecutionResult r) {
        if (id.isTest()) System.out.println((r.getStatus()==TestExecutionResult.Status.SUCCESSFUL?"PASS ":"FAIL ") + id.getDisplayName() + r.getThrowable().map(t->"  -> "+t).orElse(""));
      }
    };
    l.execute(req, sum, print);
    System.out.println("tests " + sum.getSummary().getTestsFoundCount() + ", failed " + sum.getSummary().getTotalFailureCount());
    System.exit(sum.getSummary().getTotalFailureCount() > 0 ? 1 : 0);
  }
}
