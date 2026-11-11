import re
c=open('core.js',encoding='utf-8').read().replace('</script>','<\\/script>')
c=c.replace("if (typeof module !== 'undefined') module.exports = BookCore;","")
h=open('app.html',encoding='utf-8').read().replace('/*__CORE__*/',c)
open('quet_sach.html','w',encoding='utf-8').write(h); print(len(h))
