"""Native storage fixture for testing an optimized APK without app test hooks."""
import json
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo

def shell(*args):
    return subprocess.check_output(['adb','shell',*args],text=True).strip()

zone=ZoneInfo(shell('getprop','persist.sys.timezone') or 'UTC')
epoch=int(datetime(2026,10,12,8,10,tzinfo=zone).timestamp()*1000)
elapsed=int(float(shell('cat','/proc/uptime').split()[0])*1000)
try: boot=int(shell('settings','get','global','boot_count'))
except ValueError: boot=-2
subjects=[{'id':f's{i}','name':name,'shortName':name[0],'teacher':'李老师','color':'#657E9C','outdoor':False}
          for i,name in enumerate(['语文','数学','英语','物理','地理','体育'])]
times=[(480,525),(540,585),(595,640),(650,695),(840,885),(895,940)]
slots=[{'id':f't{i}','name':f'第 {i+1} 节','start':a*60,'end':b*60,'type':'LESSON','defaultSubject':''}
       for i,(a,b) in enumerate(times)]
profile={'id':'release-fixture','name':'发布验证课表','subjects':subjects,
         'layouts':[{'id':'layout','name':'秋季作息','defaultMinutes':45,'defaultSubject':'s0','slots':slots}],
         'plans':[{'id':f'p{d}','name':f'周{"日一二三四五六"[d]}课表','layoutId':'layout',
                   'subjects':{f't{i}':f's{(i+d-1)%6}' for i in range(6)},'days':[d],
                   'cycle':1,'week':1,'anchor':'2026-10-12','offsetWeeks':0,'enabled':True,
                   'groupId':'default','layerOnly':False,'disabledSlots':[]} for d in range(1,6)],
         'groups':[{'id':'default','name':'常规课表','global':False},{'id':'global','name':'全局课表','global':True}],
         'activeGroup':'default','manualPlan':'','temporary':[]}
data={'schema':1,'profiles':[profile],'activeProfile':profile['id'],
      'settings':{'seed':'#B34E78','theme':'light','largeText':False,'highContrast':False,'reducedMotion':False,
                  'notification':True,'softwareClock':True,'autoSync':False,'schoolOffset':0,
                  'calibrationMillis':0,'syncEpoch':epoch,'syncElapsed':elapsed,'syncBootCount':boot,
                  'syncStatus':'已校时','ntpHost':'time.android.com'}}
print(json.dumps(data,ensure_ascii=False))
