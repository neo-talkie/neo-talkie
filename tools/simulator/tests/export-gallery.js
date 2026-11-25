// Evaluate this function in the local simulator page after fonts have loaded.
// JSON bundle includes deterministic PNGs and complete parameters, with no service calls.
async () => {
  await document.fonts.ready;
  const {PRESETS,preset}=await import('/model.js');
  const sim=window.radioSimulator;
  const board=await sim.boardCanvas();
  return {
    manifest:sim.manifest(),
    board:{width:board.width,height:board.height,png:board.toDataURL('image/png').split(',')[1]},
    screens:PRESETS.map(p=>{
      const c=document.createElement('canvas');sim.renderScreen(c,preset(p.id),0);
      return {id:p.id,png:c.toDataURL('image/png').split(',')[1]};
    })
  };
}
