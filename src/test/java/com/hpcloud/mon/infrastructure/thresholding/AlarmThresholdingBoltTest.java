package com.hpcloud.mon.infrastructure.thresholding;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.testng.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import backtype.storm.Testing;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.testing.MkTupleParam;
import backtype.storm.tuple.Tuple;

import com.hpcloud.mon.ThresholdingConfiguration;
import com.hpcloud.mon.common.event.AlarmUpdatedEvent;
import com.hpcloud.mon.common.model.alarm.AlarmExpression;
import com.hpcloud.mon.common.model.alarm.AlarmState;
import com.hpcloud.mon.common.model.alarm.AlarmSubExpression;
import com.hpcloud.mon.domain.model.Alarm;
import com.hpcloud.mon.domain.model.SubAlarm;
import com.hpcloud.mon.domain.service.AlarmDAO;
import com.hpcloud.streaming.storm.Streams;

@Test
public class AlarmThresholdingBoltTest {

    private static final String ALERT_ROUTING_KEY = "Alert Routing Key";
	private static final String ALERTS_EXCHANGE = "Alerts";
    private static final String tenantId = "AAAAABBBBBBCCCCC";

    private AlarmExpression alarmExpression;
    private Alarm alarm;
    private List<SubAlarm> subAlarms;

    private AlarmEventForwarder alarmEventForwarder;
    private AlarmDAO alarmDAO;
    private AlarmThresholdingBolt bolt;
    private OutputCollector collector;
    private final String[] subExpressions = {
            "avg(cpu{instance_id=123,device=42}, 1) > 5",
            "max(load{instance_id=123,device=42}, 1) > 8",
            "sum(diskio{instance_id=123,device=42}, 1) > 5000" };

    @BeforeMethod
    protected void beforeMethod() {
        final String alarmId = "111111112222222222233333333334";
        final StringBuilder builder = new StringBuilder();
        for (final String subExpression : subExpressions) {
            if (builder.length() > 0)
                builder.append(" or ");
            builder.append(subExpression);
        }
        final String expression = builder.toString();
        alarm = new Alarm();
        alarm.setName("Test CPU Alarm");
        alarm.setDescription("Description of Alarm");
        alarm.setTenantId(tenantId);
        alarm.setId(alarmId);
        alarm.setExpression(expression);
        alarm.setState(AlarmState.OK);
        alarmExpression = new AlarmExpression(expression);
        final List<AlarmSubExpression> subExpressions = alarmExpression.getSubExpressions();
        subAlarms = new ArrayList<SubAlarm>(subExpressions.size());
        for (int i = 0; i < subExpressions.size(); i++) {
            final SubAlarm subAlarm = new SubAlarm(UUID.randomUUID().toString(), alarmId, subExpressions.get(i));
            subAlarms.add(subAlarm);
        }
        alarm.setSubAlarms(subAlarms);

    	alarmEventForwarder = mock(AlarmEventForwarder.class);
    	alarmDAO = mock(AlarmDAO.class);
    	bolt = new MockAlarmThreshholdBolt(alarmDAO, alarmEventForwarder);
    	collector = mock(OutputCollector.class);
		final Map<String, String> config = new HashMap<>();
		config.put(ThresholdingConfiguration.ALERTS_EXCHANGE, ALERTS_EXCHANGE);
		config.put(ThresholdingConfiguration.ALERTS_ROUTING_KEY, ALERT_ROUTING_KEY);
		final TopologyContext context = mock(TopologyContext.class);
		bolt.prepare(config, context, collector);
    }

    /**
     * Create a simple Alarm with one sub expression.
     * Send a SubAlarm with state set to ALARM.
     * Ensure that the Alarm was triggered and sent
     */
    public void simpleAlarmCreation() {
		final SubAlarm subAlarm = subAlarms.get(0);
        final String alarmId = alarm.getId();
        when(alarmDAO.findById(alarmId)).thenReturn(alarm);
        emitSubAlarmStateChange(alarmId, subAlarm, AlarmState.ALARM);
        for (int i = 1; i < subAlarms.size(); i++) {
            emitSubAlarmStateChange(alarmId, subAlarms.get(i), AlarmState.OK);
        }
        final String alarmJson = "{\"alarm-transitioned\":{\"tenantId\":\"" + tenantId + "\"," +
				"\"alarmId\":\"111111112222222222233333333334\",\"alarmName\":\"Test CPU Alarm\"," +
				"\"alarmDescription\":\"Description of Alarm\",\"oldState\":\"OK\",\"newState\":\"ALARM\"," +
				"\"actionsEnabled\":true," +
                "\"stateChangeReason\":\"Thresholds were exceeded for the sub-alarms: [" + subAlarm.getExpression().getExpression() + "]\"," +
				"\"timestamp\":1395587091}}";

		verify(alarmEventForwarder, times(1)).send(ALERTS_EXCHANGE, ALERT_ROUTING_KEY, alarmJson);
        verify(alarmDAO, times(1)).updateState(alarmId, AlarmState.ALARM);

		// Now clear the alarm and ensure another notification gets sent out
		subAlarm.setState(AlarmState.OK);
        final Tuple clearTuple = createSubAlarmStateChangeTuple(alarmId, subAlarm);
		bolt.execute(clearTuple);
		verify(collector, times(1)).ack(clearTuple);
		final String okJson = "{\"alarm-transitioned\":{\"tenantId\":\"" + tenantId + "\"," + 
                "\"alarmId\":\"111111112222222222233333333334\",\"alarmName\":\"Test CPU Alarm\"," +
                "\"alarmDescription\":\"Description of Alarm\",\"oldState\":\"ALARM\",\"newState\":\"OK\"," +
                "\"actionsEnabled\":true," +
                "\"stateChangeReason\":\"The alarm threshold(s) have not been exceeded\",\"timestamp\":1395587091}}";
		verify(alarmEventForwarder, times(1)).send(ALERTS_EXCHANGE, ALERT_ROUTING_KEY, okJson);
        verify(alarmDAO, times(1)).updateState(alarmId, AlarmState.OK);
	}

    public void simpleAlarmUpdate() {
        String alarmId = setUpInitialAlarm();

        // Now send an AlarmUpdatedEvent
        final Map<String, AlarmSubExpression> empty = new HashMap<>();
        final String newName = "New Name";
        final String newDescription = "New Description";
        final AlarmState newState = AlarmState.OK;
        boolean newEnabled = false;
        final AlarmUpdatedEvent event = new AlarmUpdatedEvent(tenantId, alarmId, newName, newDescription, alarm.getAlarmExpression().getExpression(),
                newState, newEnabled, empty, empty, empty);
        final Tuple updateTuple = createAlarmUpdateTuple(event);
        bolt.execute(updateTuple);
        verify(collector, times(1)).ack(updateTuple);
        assertEquals(alarm.getName(), newName);
        assertEquals(alarm.getState(), newState);
        assertEquals(alarm.isActionsEnabled(), newEnabled);
    }

    public void complexAlarmUpdate() {
        String alarmId = setUpInitialAlarm();

        // Now send an AlarmUpdatedEvent
        final Map<String, AlarmSubExpression> newSubExpressions = new HashMap<>();
        final Map<String, AlarmSubExpression> oldSubExpressions = new HashMap<>();
        final Map<String, AlarmSubExpression> changedSubExpressions = new HashMap<>();
        final String newExpression = subExpressions[1] + " or " +
                                          subExpressions[2].replace("max", "avg") + " or " +
                                          "sum(diskio{instance_id=123,device=4242}, 1) > 5000";

        final AlarmExpression newAlarmExpression = new AlarmExpression(newExpression);
        final SubAlarm newSubAlarm = new SubAlarm(UUID.randomUUID().toString(), alarmId, newAlarmExpression.getSubExpressions().get(2));
        newSubExpressions.put(newSubAlarm.getId(), newSubAlarm.getExpression());
        final SubAlarm deletedSubAlarm = subAlarms.get(0);
        oldSubExpressions.put(deletedSubAlarm.getId(), deletedSubAlarm.getExpression());
        final SubAlarm changedSubAlarm = new SubAlarm(subAlarms.get(2).getId(), alarmId, newAlarmExpression.getSubExpressions().get(1));
        changedSubExpressions.put(changedSubAlarm.getId(), changedSubAlarm.getExpression());
        final SubAlarm unChangedSubAlarm = new SubAlarm(subAlarms.get(1).getId(), alarmId, subAlarms.get(1).getExpression());

        emitSubAlarmStateChange(alarmId, changedSubAlarm, AlarmState.OK);
        emitSubAlarmStateChange(alarmId, unChangedSubAlarm, AlarmState.OK);
        unChangedSubAlarm.setState(AlarmState.OK);

        final AlarmUpdatedEvent event = new AlarmUpdatedEvent(tenantId, alarmId, alarm.getName(), alarm.getDescription(), newExpression,
                alarm.getState(), alarm.isActionsEnabled(), oldSubExpressions, changedSubExpressions, newSubExpressions);
        final Tuple updateTuple = createAlarmUpdateTuple(event);
        bolt.execute(updateTuple);
        verify(collector, times(1)).ack(updateTuple);

        final Alarm changedAlarm = bolt.alarms.get(alarmId);
        assertEquals(changedAlarm.getAlarmExpression(), newAlarmExpression);
        assertEquals(changedAlarm.getSubAlarms().size(), 3);
        assertEquals(changedAlarm.getSubAlarm(unChangedSubAlarm.getId()), unChangedSubAlarm);
        assertEquals(changedAlarm.getSubAlarm(newSubAlarm.getId()), newSubAlarm);
        changedSubAlarm.setState(AlarmState.OK);
        assertEquals(changedAlarm.getSubAlarm(changedSubAlarm.getId()), changedSubAlarm);
        assertEquals(changedSubAlarm.isNoState(), false);
    }

    private String setUpInitialAlarm() {
        final String alarmId = alarm.getId();
        when(alarmDAO.findById(alarmId)).thenReturn(alarm);
        // Load up the original Alarm
        emitSubAlarmStateChange(alarmId, subAlarms.get(0), AlarmState.ALARM);
        return alarmId;
    }

    private void emitSubAlarmStateChange(String alarmId,
            final SubAlarm subAlarm, AlarmState state) {
        // Create a copy so changing the state doesn't directly update the ones in the bolt
        final SubAlarm toEmit = new SubAlarm(subAlarm.getId(), subAlarm.getAlarmId(), subAlarm.getExpression());
        toEmit.setState(state);
        final Tuple tuple = createSubAlarmStateChangeTuple(alarmId, toEmit);
        bolt.execute(tuple);
        verify(collector, times(1)).ack(tuple);
    }

    private Tuple createAlarmUpdateTuple(AlarmUpdatedEvent event) {
        final MkTupleParam tupleParam = new MkTupleParam();
        tupleParam.setFields(EventProcessingBolt.ALARM_EVENT_STREAM_FIELDS);
        tupleParam.setStream(EventProcessingBolt.ALARM_EVENT_STREAM_ID);
        final Tuple tuple = Testing.testTuple(Arrays.asList(EventProcessingBolt.UPDATED, event.alarmId, event), tupleParam);
        return tuple;
    }

    private Tuple createSubAlarmStateChangeTuple(String alarmId, final SubAlarm subAlarm) {
        final MkTupleParam tupleParam = new MkTupleParam();
        tupleParam.setFields("alarmId", "subAlarm");
        tupleParam.setStream(Streams.DEFAULT_STREAM_ID);
        final Tuple tuple = Testing.testTuple(Arrays.asList(alarmId, subAlarm), tupleParam);
        return tuple;
    }

    private class MockAlarmThreshholdBolt extends AlarmThresholdingBolt {

		private static final long serialVersionUID = 1L;

		public MockAlarmThreshholdBolt(AlarmDAO alarmDAO,
				AlarmEventForwarder alarmEventForwarder) {
			super(alarmDAO, alarmEventForwarder);
		}

		@Override
		protected long getTimestamp() {
			// Have to keep the time stamp constant so JSON comparison works
			return 1395587091;
		}
    }
}
